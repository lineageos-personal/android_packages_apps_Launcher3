/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.uioverrides.touchcontrollers

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Utilities.EDGE_NAV_BAR
import com.android.launcher3.Utilities.boundToRange
import com.android.launcher3.Utilities.debugLog
import com.android.launcher3.Utilities.isRtl
import com.android.launcher3.touch.SingleAxisSwipeDetector
import com.android.launcher3.util.DisplayController
import com.android.launcher3.util.TouchController
import com.android.quickstep.LockedTaskManager
import com.android.quickstep.views.RecentsDismissUtils
import com.android.quickstep.views.RecentsView
import com.android.quickstep.views.RecentsViewContainer
import com.android.quickstep.views.TaskView
import com.google.android.msdl.data.model.MSDLToken
import com.android.launcher3.util.MSDLPlayerWrapper
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Touch controller which handles dragging task view cards for lock/unlock. */
class TaskViewLaunchTouchController<CONTAINER>(
    private val container: CONTAINER,
    private val taskViewRecentsTouchContext: TaskViewRecentsTouchContext,
) : TouchController, SingleAxisSwipeDetector.Listener where
CONTAINER : Context,
CONTAINER : RecentsViewContainer {
    private val tempRect = Rect()
    private val recentsView: RecentsView<*, *> = container.getOverviewPanel()
    private val detector: SingleAxisSwipeDetector =
        SingleAxisSwipeDetector(
            container as Context,
            this,
            recentsView.pagedOrientationHandler.upDownSwipeDirection,
        )
    private val isRtl = isRtl(container.resources)
    private var taskBeingDragged: TaskView? = null
    private var maxLockDisplacement: Float = 0f
    private var dismissLength: Int = 0
    private var verticalFactor: Int = 0
    private var dismissVerticalFactor: Int = 0
    private var dragMode = DragMode.REST
    private var canInterceptTouch = false
    private var wasLockedBeforeDrag = false
    private var hapticThresholdState = HapticThresholdState.CANCEL

    private fun canTaskLockTaskView(taskView: TaskView?) =
        taskView != null && DisplayController.getNavigationMode(container).hasGestures

    private fun canInterceptTouch(ev: MotionEvent): Boolean =
        when {
            // Don't intercept swipes on the nav bar, as user might be trying to go home during a
            // task dismiss animation.
            (ev.edgeFlags and EDGE_NAV_BAR) != 0 -> {
                debugLog(TAG, "Not intercepting edge swipe on nav bar.")
                false
            }

            // Floating views that a TouchController should not try to intercept touches from.
            AbstractFloatingView.getTopOpenViewWithType(
                container,
                AbstractFloatingView.TYPE_TOUCH_CONTROLLER_NO_INTERCEPT,
            ) != null -> {
                debugLog(TAG, "Not intercepting, open floating view blocking touch.")
                false
            }

            // Disable swiping if the task overlay is modal.
            taskViewRecentsTouchContext.isRecentsModal -> {
                debugLog(TAG, "Not intercepting touch in modal overlay.")
                false
            }

            // Do not allow lock while recents is scrolling.
            !recentsView.scroller.isFinished -> {
                debugLog(TAG, "Not intercepting touch, recents scrolling.")
                false
            }

            else ->
                taskViewRecentsTouchContext.isRecentsInteractive.also { isRecentsInteractive ->
                    if (!isRecentsInteractive) {
                        debugLog(TAG, "Not intercepting touch, recents not interactive.")
                    }
                }
        }

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (
            (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL)
        ) {
            clearState()
        }
        if (ev.action == MotionEvent.ACTION_DOWN) {
            canInterceptTouch = onActionDown(ev)
            if (!canInterceptTouch) {
                clearState()
                return false
            }
        }
        // Ignore other actions if touch intercepting has not been enabled in an ACTION_DOWN event.
        if (!canInterceptTouch) {
            return false
        }
        onControllerTouchEvent(ev)
        return detector.isDraggingState
    }

    override fun onControllerTouchEvent(ev: MotionEvent) = detector.onTouchEvent(ev)

    private fun onActionDown(ev: MotionEvent): Boolean {
        if (!canInterceptTouch(ev)) {
            return false
        }
        taskBeingDragged =
            recentsView.findTopMostTaskUnderEvent(ev)?.also {
                verticalFactor =
                    recentsView.pagedOrientationHandler.getTaskDragDisplacementFactor(isRtl)
            }
        if (!canTaskLockTaskView(taskBeingDragged)) {
            debugLog(TAG, "Not intercepting touch, task cannot be locked.")
            return false
        }
        detector.setDetectableScrollConditions(
            SingleAxisSwipeDetector.DIRECTION_BOTH,
            /* ignoreSlop= */ false,
        )
        return true
    }

    override fun onDragStart(start: Boolean, startDisplacement: Float) {
        val taskBeingDragged = taskBeingDragged ?: return
        debugLog(TAG, "Handling lock touch event.")

        val secondaryLayerDimension: Int =
            recentsView.pagedOrientationHandler.getSecondaryDimension(container.getDragLayer())
        taskBeingDragged.getThumbnailBounds(tempRect, /* relativeToDragLayer= */ true)
        val taskDismissLength =
            recentsView.pagedOrientationHandler.getTaskDismissLength(
                secondaryLayerDimension, tempRect
            )
        maxLockDisplacement = ceil(taskDismissLength * LOCK_DISPLACEMENT_FRACTION)
            .toFloat() * verticalFactor
        dismissLength = ceil(taskDismissLength / RECENTS_SCALE_ON_DISMISS_SUCCESS).toInt()
        dismissVerticalFactor = recentsView.pagedOrientationHandler.getTaskDismissVerticalDirection()

        taskBeingDragged.translationZ = 0.1f

        wasLockedBeforeDrag = taskBeingDragged.isLocked
        hapticThresholdState = HapticThresholdState.CANCEL
        dragMode = DragMode.REST

        showLockPill(wasLockedBeforeDrag)
    }

    override fun onDrag(displacement: Float): Boolean {
        val taskBeingDragged = taskBeingDragged ?: return false
        val isGoingUp = recentsView.pagedOrientationHandler.isGoingUp(displacement, isRtl)
        val displacementAbs = abs(displacement)
        val boundedDisplacement =
            when {
                displacementAbs < CANCEL_DISPLACEMENT_EPSILON -> {
                    dragMode = DragMode.REST
                    hideLockPill()
                    taskBeingDragged.isBeingDraggedForDismissal = false
                    0f
                }
                isGoingUp -> {
                    dragMode = DragMode.DISMISS
                    hideLockPill()
                    taskBeingDragged.isBeingDraggedForDismissal = true
                    boundToRange(displacementAbs, 0f, dismissLength.toFloat()) * dismissVerticalFactor
                }
                else -> {
                    dragMode = DragMode.LOCK
                    showLockPill(wasLockedBeforeDrag)
                    taskBeingDragged.isBeingDraggedForDismissal = false
                    boundToRange(displacementAbs, 0f, abs(maxLockDisplacement)) * verticalFactor
                }
            }
        taskBeingDragged.secondaryDismissTranslationProperty.setValue(
            taskBeingDragged, boundedDisplacement
        )
        if (taskBeingDragged.isRunningTask && recentsView.enableDrawingLiveTile) {
            recentsView.runActionOnRemoteHandles { remoteTargetHandle ->
                remoteTargetHandle.taskViewSimulator.taskSecondaryTranslation.value =
                    boundedDisplacement
            }
            recentsView.redrawLiveTile()
        }
        updateThresholdHaptic(displacementAbs)
        return true
    }

    private fun updateThresholdHaptic(displacementAbs: Float) {
        val newState =
            when {
                dragMode == DragMode.LOCK &&
                    displacementAbs >= abs(LOCK_THRESHOLD_FRACTION * maxLockDisplacement) ->
                    HapticThresholdState.LOCK
                dragMode == DragMode.DISMISS &&
                    displacementAbs >= DISMISS_THRESHOLD_FRACTION * dismissLength ->
                    HapticThresholdState.DISMISS
                else -> HapticThresholdState.CANCEL
            }
        if (newState == hapticThresholdState) return

        hapticThresholdState = newState
        MSDLPlayerWrapper.INSTANCE.get(recentsView.context)
            .playToken(MSDLToken.SWIPE_THRESHOLD_INDICATOR)
    }

    override fun onDragEnd(velocity: Float) {
        val taskBeingDragged = taskBeingDragged ?: return
        val currentDisplacement =
            taskBeingDragged.secondaryDismissTranslationProperty.get(taskBeingDragged)
        val isDismissDrag = dragMode == DragMode.DISMISS

        if (!isDismissDrag) {
            val isBeyondLockThreshold =
                dragMode == DragMode.LOCK &&
                    abs(currentDisplacement) > abs(LOCK_THRESHOLD_FRACTION * maxLockDisplacement)

            if (isBeyondLockThreshold) {
                val packageName = taskBeingDragged.firstTask?.key?.packageName
                if (packageName != null) {
                    LockedTaskManager.getInstance(container).setPackageLocked(
                        packageName, !wasLockedBeforeDrag
                    )
                    taskBeingDragged.updateLockState(packageName)
                }
            }
        }

        hideLockPill()

        val isBeyondDismissThreshold =
            abs(currentDisplacement) > abs(DISMISS_THRESHOLD_FRACTION * dismissLength)
        val velocityIsGoingUp = recentsView.pagedOrientationHandler.isGoingUp(velocity, isRtl)
        val isFlingingTowardsDismiss = detector.isFling(velocity) && velocityIsGoingUp
        val isFlingingTowardsRestState = detector.isFling(velocity) && !velocityIsGoingUp
        val shouldDismiss =
            isDismissDrag &&
                recentsView.canRemoveTaskView(taskBeingDragged) &&
                (isFlingingTowardsDismiss ||
                    (isBeyondDismissThreshold && !isFlingingTowardsRestState))
        val settlingDismissLength =
            if (isDismissDrag) dismissLength else abs(maxLockDisplacement).roundToInt()
        val finalPosition =
            if (shouldDismiss) (dismissLength * dismissVerticalFactor).toFloat() else 0f
        val dismissThreshold =
            if (isDismissDrag) {
                (DISMISS_THRESHOLD_FRACTION * dismissLength * dismissVerticalFactor).roundToInt()
            } else {
                (LOCK_THRESHOLD_FRACTION * settlingDismissLength).roundToInt()
            }
        recentsView.runTaskDismissSettlingSpringAnimation(
            taskBeingDragged,
            shouldDismiss,
            RecentsDismissUtils.DismissedTaskData(
                velocity,
                settlingDismissLength,
                finalPosition,
                dismissThreshold,
            ),
            shouldDismiss,
            false,
        )?.addEndListener {
            taskBeingDragged.secondaryDismissTranslationProperty.setValue(taskBeingDragged, 0f)
            taskBeingDragged.isBeingDraggedForDismissal = false
            if (taskBeingDragged.isRunningTask) {
                recentsView.runActionOnRemoteHandles { remoteTargetHandle ->
                    remoteTargetHandle.taskViewSimulator.taskSecondaryTranslation.value = 0f
                }
                recentsView.redrawLiveTile()
            }
            taskBeingDragged.translationZ = 0f
            taskBeingDragged.isBeingDismissed = false
        }
    }

    private fun showLockPill(isCurrentlyLocked: Boolean) {
        val actionsView = container.actionsView ?: return
        actionsView.showLockPill(isCurrentlyLocked)
    }

    private fun hideLockPill() {
        val actionsView = container.actionsView ?: return
        actionsView.hideLockPill()
    }

    private fun clearState() {
        detector.finishedScrolling()
        detector.setDetectableScrollConditions(0, false)
        taskBeingDragged?.let {
            it.secondaryDismissTranslationProperty.setValue(it, 0f)
            it.isBeingDraggedForDismissal = false
            if (it.isRunningTask) {
                recentsView.runActionOnRemoteHandles { remoteTargetHandle ->
                    remoteTargetHandle.taskViewSimulator.taskSecondaryTranslation.value = 0f
                }
                recentsView.redrawLiveTile()
            }
            it.translationZ = 0f
        }
        hideLockPill()
        dragMode = DragMode.REST
        hapticThresholdState = HapticThresholdState.CANCEL
        taskBeingDragged = null
    }

    private enum class DragMode {
        REST,
        LOCK,
        DISMISS,
    }

    private enum class HapticThresholdState {
        LOCK,
        CANCEL,
        DISMISS,
    }

    companion object {
        private const val TAG = "TaskViewLaunchTouchController"
        private const val LOCK_DISPLACEMENT_FRACTION = 0.4f
        private const val LOCK_THRESHOLD_FRACTION = 0.5f
        private const val DISMISS_THRESHOLD_FRACTION = 0.5f
        private const val CANCEL_DISPLACEMENT_EPSILON = 1f
        private const val RECENTS_SCALE_ON_DISMISS_SUCCESS = 0.975f
    }
}
