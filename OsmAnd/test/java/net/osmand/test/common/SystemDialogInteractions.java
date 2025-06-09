package net.osmand.test.common;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.IdRes;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.action.GeneralClickAction;
import androidx.test.espresso.action.Press;
import androidx.test.espresso.action.Tap;

import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.Espresso.onView;


import org.hamcrest.Matcher;
import org.jetbrains.annotations.NotNull;

public class SystemDialogInteractions {

	public static View getViewById(@IdRes int id) {
		final View[] capturedView = new View[1];
		onView(withId(id)).perform(new ViewAction() {
			@Override
			public Matcher<View> getConstraints() {
				return isAssignableFrom(View.class); // or use any matcher suitable
			}

			@Override
			public String getDescription() {
				return "Capture view reference";
			}

			@Override
			public void perform(UiController uiController, View view) {
				capturedView[0] = view;
			}
		});
		return capturedView[0];
	}

	public static ViewAction clickInView(final float x, final float y) {
		return new GeneralClickAction(
				Tap.SINGLE,
				view -> {
					final int[] location = new int[2];
					view.getLocationOnScreen(location);
					return new float[] {location[0] + x, location[1] + y};
				},
				Press.FINGER
		);
	}


	//finds n-th child of given type at one hierarchy level
	public static <T extends View> T findDescendantOfType(View parent, Class<T> targetClass, int targetIndex) {
		if (targetIndex < 0) {
			throw new IllegalArgumentException("targetIndex should be 0 or more");
		}
		if (targetClass.isInstance(parent) && targetIndex == 0) {
			return targetClass.cast(parent);
		}

		if (parent instanceof ViewGroup group) {
			int levelTargetIndex = targetIndex;
			for (int i = 0; i < group.getChildCount(); i++) {
				View child = group.getChildAt(i);
				T result = findDescendantOfType(child, targetClass, targetIndex);
				if (result != null) {
					if (levelTargetIndex == 0) {
						return result;
					} else {
						levelTargetIndex--;
					}
				}
			}
		}
		return null;
	}


	public static void waitForAnyView(long timeoutMs, long pollIntervalMs, Matcher<View>... matchers) {
		long startTime = System.currentTimeMillis();

		while (System.currentTimeMillis() - startTime < timeoutMs) {
			for (Matcher<View> matcher : matchers) {
				try {
					onView(matcher).check(matches(isDisplayed()));
					return; // One of the views is displayed
				} catch (NoMatchingViewException | AssertionError e) {
					// Ignore and try next
				}
			}
			try {
				Thread.sleep(pollIntervalMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Interrupted while waiting for view", e);
			}
		}
	}

	public static boolean waitForViewDisappeared(long timeoutMs, long pollIntervalMs, @NotNull Matcher<View> matcher) {
		long startTime = System.currentTimeMillis();
		while (System.currentTimeMillis() - startTime < timeoutMs) {
			try {
				onView(matcher).check(matches(isDisplayed()));
			} catch (NoMatchingViewException | AssertionError e) {
				return true; // View is not displayed
			}

			try {
				Thread.sleep(pollIntervalMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Interrupted while waiting for view", e);
			}
		}
		return false;
	}

	public static boolean isViewVisible(Matcher<View> matcher) {
		try {
			onView(matcher).check(matches(isDisplayed()));
			return true;
		} catch (NoMatchingViewException | AssertionError e) {
			return false;
		}
	}

}
