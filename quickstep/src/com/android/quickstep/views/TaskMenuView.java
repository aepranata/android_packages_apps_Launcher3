/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.quickstep.views;

import static com.android.quickstep.util.NavigationModeFeatureFlag.LIVE_TILE;
import static com.android.quickstep.views.TaskThumbnailView.DIM_ALPHA;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BaseDraggingActivity;
import com.android.launcher3.R;
import com.android.launcher3.anim.AnimationSuccessListener;
import com.android.launcher3.anim.Interpolators;
import com.android.launcher3.anim.RoundedRectRevealOutlineProvider;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.popup.SystemShortcut;
import com.android.launcher3.touch.PagedOrientationHandler;
import com.android.launcher3.views.BaseDragLayer;
import com.android.quickstep.TaskOverlayFactory;
import com.android.quickstep.TaskUtils;
import com.android.quickstep.util.TaskCornerRadius;

/**
 * Contains options for a recent task when long-pressing its icon.
 */
public class TaskMenuView extends AbstractFloatingView {

    private static final Rect sTempRect = new Rect();

    private static final int REVEAL_OPEN_DURATION = 150;
    private static final int REVEAL_CLOSE_DURATION = 100;

    private BaseDraggingActivity mActivity;
    private TextView mTaskName;
    private AnimatorSet mOpenCloseAnimator;
    private TaskView mTaskView;
    private LinearLayout mOptionLayout;

    public TaskMenuView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskMenuView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mActivity = BaseDraggingActivity.fromContext(context);
        setClipToOutline(true);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTaskName = findViewById(R.id.task_name);
        mOptionLayout = findViewById(R.id.menu_option_layout);
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            BaseDragLayer dl = mActivity.getDragLayer();
            if (!dl.isEventOverView(this, ev)) {
                // TODO: log this once we have a new container type for it?
                close(true);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void handleClose(boolean animate) {
        if (animate) {
            animateClose();
        } else {
            closeComplete();
        }
    }

    @Override
    protected boolean isOfType(int type) {
        return (type & TYPE_TASK_MENU) != 0;
    }

    @Override
    public ViewOutlineProvider getOutlineProvider() {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        TaskCornerRadius.get(view.getContext()));
            }
        };
    }

    public void setPosition(float x, float y, PagedOrientationHandler pagedOrientationHandler) {
        int taskTopMargin = mActivity.getDeviceProfile().overviewTaskThumbnailTopMarginPx;
        float adjustedY = y + taskTopMargin;
        // Changing pivot to make computations easier
        // NOTE: Changing the pivots means the rotated view gets rotated about the new pivots set,
        // which would render the X and Y position set here incorrect
        setPivotX(0);
        if (mActivity.getDeviceProfile().isTablet && FeatureFlags.ENABLE_OVERVIEW_GRID.get()) {
            // In tablet, set pivotY to original position without mThumbnailTopMargin adjustment.
            setPivotY(-taskTopMargin);
        } else {
            setPivotY(0);
        }
        setRotation(pagedOrientationHandler.getDegreesRotated());
        setX(pagedOrientationHandler.getTaskMenuX(x, mTaskView.getThumbnail()));
        setY(pagedOrientationHandler.getTaskMenuY(adjustedY, mTaskView.getThumbnail()));
    }

    public void onRotationChanged() {
        if (mOpenCloseAnimator != null && mOpenCloseAnimator.isRunning()) {
            mOpenCloseAnimator.end();
        }
        if (mIsOpen) {
            mOptionLayout.removeAllViews();
            if (!populateAndLayoutMenu()) {
                close(false);
            }
        }
    }

    public static TaskMenuView showForTask(TaskView taskView) {
        BaseDraggingActivity activity = BaseDraggingActivity.fromContext(taskView.getContext());
        final TaskMenuView taskMenuView = (TaskMenuView) activity.getLayoutInflater().inflate(
                        R.layout.task_menu, activity.getDragLayer(), false);
        return taskMenuView.populateAndShowForTask(taskView) ? taskMenuView : null;
    }

    private boolean populateAndShowForTask(TaskView taskView) {
        if (isAttachedToWindow()) {
            return false;
        }
        mActivity.getDragLayer().addView(this);
        mTaskView = taskView;
        if (!populateAndLayoutMenu()) {
            return false;
        }
        post(this::animateOpen);
        return true;
    }

    /** @return true if successfully able to populate task view menu, false otherwise */
    private boolean populateAndLayoutMenu() {
        if (mTaskView.getTask().icon == null) {
            // Icon may not be loaded
            return false;
        }
        addMenuOptions(mTaskView);
        orientAroundTaskView(mTaskView);
        return true;
    }

    private void addMenuOptions(TaskView taskView) {
        mTaskName.setText(TaskUtils.getTitle(getContext(), taskView.getTask()));
        mTaskName.setOnClickListener(v -> close(true));

        TaskOverlayFactory.getEnabledShortcuts(taskView, mActivity.getDeviceProfile())
                .forEach(this::addMenuOption);
    }

    private void addMenuOption(SystemShortcut menuOption) {
        ViewGroup menuOptionView = (ViewGroup) mActivity.getLayoutInflater().inflate(
                R.layout.task_view_menu_option, this, false);
        menuOption.setIconAndLabelFor(
                menuOptionView.findViewById(R.id.icon), menuOptionView.findViewById(R.id.text));
        LayoutParams lp = (LayoutParams) menuOptionView.getLayoutParams();
        mTaskView.getPagedOrientationHandler().setLayoutParamsForTaskMenuOptionItem(lp);
        menuOptionView.setEnabled(menuOption.isEnabled());
        menuOptionView.setAlpha(menuOption.isEnabled() ? 1 : 0.5f);
        menuOptionView.setOnClickListener(view -> {
            if (LIVE_TILE.get()) {
                RecentsView recentsView = mTaskView.getRecentsView();
                recentsView.switchToScreenshot(null,
                        () -> recentsView.finishRecentsAnimation(true /* toRecents */,
                                () -> menuOption.onClick(view)));
            } else {
                menuOption.onClick(view);
            }
        });
        mOptionLayout.addView(menuOptionView);
    }

    private void orientAroundTaskView(TaskView taskView) {
        PagedOrientationHandler orientationHandler = taskView.getPagedOrientationHandler();
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        mActivity.getDragLayer().getDescendantRectRelativeToSelf(taskView, sTempRect);
        Rect insets = mActivity.getDragLayer().getInsets();
        BaseDragLayer.LayoutParams params = (BaseDragLayer.LayoutParams) getLayoutParams();
        params.width = orientationHandler.getTaskMenuWidth(taskView.getThumbnail());
        // Gravity set to Left instead of Start as sTempRect.left measures Left distance not Start
        params.gravity = Gravity.LEFT;
        setLayoutParams(params);
        setScaleX(taskView.getScaleX());
        setScaleY(taskView.getScaleY());
        boolean canActivityRotate = taskView.getRecentsView()
            .mOrientationState.isRecentsActivityRotationAllowed();
        mOptionLayout.setOrientation(orientationHandler
                .getTaskMenuLayoutOrientation(canActivityRotate, mOptionLayout));
        setPosition(sTempRect.left - insets.left, sTempRect.top - insets.top,
            taskView.getPagedOrientationHandler());
    }

    private void animateOpen() {
        animateOpenOrClosed(false);
        mIsOpen = true;
    }

    private void animateClose() {
        animateOpenOrClosed(true);
    }

    private void animateOpenOrClosed(boolean closing) {
        if (mOpenCloseAnimator != null && mOpenCloseAnimator.isRunning()) {
            mOpenCloseAnimator.end();
        }
        mOpenCloseAnimator = new AnimatorSet();

        final Animator revealAnimator = createOpenCloseOutlineProvider()
                .createRevealAnimator(this, closing);
        revealAnimator.setInterpolator(Interpolators.DEACCEL);
        mOpenCloseAnimator.playTogether(revealAnimator,
                ObjectAnimator.ofFloat(
                        mTaskView.getThumbnail(), DIM_ALPHA,
                        closing ? 0 : TaskView.MAX_PAGE_SCRIM_ALPHA),
                ObjectAnimator.ofFloat(this, ALPHA, closing ? 0 : 1));
        mOpenCloseAnimator.addListener(new AnimationSuccessListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                setVisibility(VISIBLE);
            }

            @Override
            public void onAnimationSuccess(Animator animator) {
                if (closing) {
                    closeComplete();
                }
            }
        });
        mOpenCloseAnimator.setDuration(closing ? REVEAL_CLOSE_DURATION: REVEAL_OPEN_DURATION);
        mOpenCloseAnimator.start();
    }

    private void closeComplete() {
        mIsOpen = false;
        mActivity.getDragLayer().removeView(this);
    }

    private RoundedRectRevealOutlineProvider createOpenCloseOutlineProvider() {
        float radius = TaskCornerRadius.get(mContext);
        Rect fromRect = new Rect(0, 0, getWidth(), 0);
        Rect toRect = new Rect(0, 0, getWidth(), getHeight());
        return new RoundedRectRevealOutlineProvider(radius, radius, fromRect, toRect);
    }

    public View findMenuItemByText(String text) {
        for (int i = mOptionLayout.getChildCount() - 1; i >= 0; --i) {
            final ViewGroup menuOptionView = (ViewGroup) mOptionLayout.getChildAt(i);
            if (text.equals(menuOptionView.<TextView>findViewById(R.id.text).getText())) {
                return menuOptionView;
            }
        }
        return null;
    }
}
