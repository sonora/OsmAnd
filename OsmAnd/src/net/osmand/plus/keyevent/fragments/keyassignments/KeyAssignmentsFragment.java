package net.osmand.plus.keyevent.fragments.keyassignments;

import static net.osmand.plus.keyevent.fragments.keyassignments.KeyAssignmentsController.PROCESS_ID;
import static net.osmand.plus.settings.fragments.BaseSettingsFragment.APP_MODE_KEY;
import static net.osmand.plus.utils.AndroidUtils.getNavigationIconResId;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.base.BaseOsmAndFragment;
import net.osmand.plus.base.dialog.interfaces.dialog.IAskRefreshDialogCompletely;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.keyevent.InputDevicesHelper;
import net.osmand.plus.keyevent.listener.EventType;
import net.osmand.plus.keyevent.listener.InputDevicesEventListener;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.widgets.dialogbutton.DialogButton;

public class KeyAssignmentsFragment extends BaseOsmAndFragment
		implements IAskRefreshDialogCompletely, InputDevicesEventListener {

	public static final String TAG = KeyAssignmentsFragment.class.getSimpleName();

	private KeyAssignmentsAdapter adapter;
	private KeyAssignmentsController controller;

	private ApplicationMode appMode;
	private InputDevicesHelper deviceHelper;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Bundle arguments = getArguments();
		String appModeKey = arguments != null ? arguments.getString(APP_MODE_KEY) : "";
		appMode = ApplicationMode.valueOfStringKey(appModeKey, settings.getApplicationMode());
		controller = KeyAssignmentsController.getInstance(app);

		deviceHelper = app.getInputDeviceHelper();
		app.getDialogManager().register(PROCESS_ID, this);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		updateNightMode();
		View view = themedInflater.inflate(R.layout.fragment_key_assignments_list, container, false);
		AndroidUtils.addStatusBarPadding21v(requireMyActivity(), view);
		setupToolbar(view);
		updateFabButton(view);
		updateSaveButton(view);

		adapter = new KeyAssignmentsAdapter(app, appMode, controller);
		RecyclerView recyclerView = view.findViewById(R.id.recycler_view);
		recyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
		recyclerView.setAdapter(adapter);
		updateViewContent();
		return view;
	}

	private void setupToolbar(@NonNull View view) {
		Toolbar toolbar = view.findViewById(R.id.toolbar);
		ImageView closeButton = toolbar.findViewById(R.id.close_button);
		closeButton.setOnClickListener(v -> {
			if (controller.isInEditMode()) {
				askExitEditMode(view);
			} else {
				dismiss();
			}
		});
		toolbar.findViewById(R.id.toolbar_subtitle).setVisibility(View.GONE);

		View actionButton = toolbar.findViewById(R.id.action_button);
		if (controller.isDeviceTypeEditable()) {
			actionButton.setOnClickListener(v -> {
				if (controller.isInEditMode()) {
					controller.askRemoveAllAssignments();
				} else {
					enterEditMode(view);
				}
			});
		} else {
			actionButton.setVisibility(View.GONE);
		}
		ViewCompat.setElevation(view.findViewById(R.id.appbar), 5.0f);
		updateToolbar(view);
	}

	private void updateToolbar(@NonNull View view) {
		Toolbar toolbar = view.findViewById(R.id.toolbar);
		boolean editMode = controller.isInEditMode();

		ImageView closeButton = toolbar.findViewById(R.id.close_button);
		int navIconId = editMode ? R.drawable.ic_action_close : getNavigationIconResId(app);
		closeButton.setImageResource(navIconId);

		TextView title = toolbar.findViewById(R.id.toolbar_title);
		title.setText(editMode ? R.string.shared_string_edit : R.string.key_assignments);

		View actionButton = toolbar.findViewById(R.id.action_button);
		ImageButton ivActionButton = actionButton.findViewById(R.id.action_button_icon);

		boolean enabled = controller.hasAssignments() || editMode;
		int actionIconId = editMode ? R.drawable.ic_action_key_assignment_remove : R.drawable.ic_action_edit_outlined;
		int actionIconColor = enabled ? ColorUtilities.getPrimaryIconColor(app, nightMode) : ColorUtilities.getDisabledTextColor(app, nightMode);
		ivActionButton.setImageDrawable(getPaintedContentIcon(actionIconId, actionIconColor));
		actionButton.setEnabled(enabled);
	}

	@Override
	public void processInputDevicesEvent(@NonNull ApplicationMode appMode, @NonNull EventType event) {
		if (event.isAssignmentRelated()) {
			updateViewContent();
		}
	}

	@Override
	public void onAskRefreshDialogCompletely(@NonNull String processId) {
		updateViewContent();
	}

	private void updateViewContent() {
		adapter.setScreenData(controller.populateScreenItems(), controller.isDeviceTypeEditable());
	}

	private void enterEditMode(@NonNull View view) {
		controller.enterEditMode();
		onScreenModeChange(view);
	}

	private void askExitEditMode(@NonNull View view) {
		// todo check changes
		controller.exitEditMode();
		onScreenModeChange(view);
	}

	private void onScreenModeChange(@NonNull View view) {
		updateToolbar(view);
		updateViewContent();
	}

	private void updateSaveButton(@NonNull View view) {
		View bottomButtons = view.findViewById(R.id.bottom_buttons);
		bottomButtons.setVisibility(controller.isInEditMode() ? View.VISIBLE : View.GONE);
		DialogButton saveButton = view.findViewById(R.id.save_button);
		saveButton.setOnClickListener(v -> {
			controller.saveChanges();
			dismiss();
		});
	}

	private void updateFabButton(@NonNull View view) {
		FloatingActionButton addButton = view.findViewById(R.id.fabButton);
		addButton.setVisibility(!controller.isInEditMode() ? View.VISIBLE : View.GONE);
		addButton.setOnClickListener(v -> controller.askAddAssignment());
	}

	@Override
	public void onResume() {
		super.onResume();
		MapActivity mapActivity = getMapActivity();
		if (mapActivity != null) {
			mapActivity.disableDrawer();
			controller.setActivity(mapActivity);
		}
		deviceHelper.addListener(this);
	}

	@Override
	public void onPause() {
		super.onPause();
		MapActivity mapActivity = getMapActivity();
		if (mapActivity != null) {
			mapActivity.enableDrawer();
		}
		controller.setActivity(null);
		deviceHelper.removeListener(this);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		FragmentActivity activity = getActivity();
		if (activity != null && !activity.isChangingConfigurations()) {
			app.getDialogManager().unregister(PROCESS_ID);
		}
	}

	private void dismiss() {
		FragmentActivity activity = getActivity();
		if (activity != null) {
			activity.onBackPressed();
		}
	}

	@Nullable
	private MapActivity getMapActivity() {
		return (MapActivity) getActivity();
	}

	@Override
	public int getStatusBarColorId() {
		AndroidUiHelper.setStatusBarContentColor(getView(), nightMode);
		return ColorUtilities.getStatusBarSecondaryColorId(nightMode);
	}

	public boolean getContentStatusBarNightMode() {
		return nightMode;
	}

	public static void showInstance(@NonNull OsmandApplication app,
	                                @NonNull FragmentManager manager,
	                                @NonNull ApplicationMode appMode) {
		if (AndroidUtils.isFragmentCanBeAdded(manager, TAG)) {
			KeyAssignmentsController.registerInstance(app, appMode, false);
			KeyAssignmentsFragment fragment = new KeyAssignmentsFragment();
			Bundle arguments = new Bundle();
			arguments.putString(APP_MODE_KEY, appMode.getStringKey());
			fragment.setArguments(arguments);
			manager.beginTransaction()
					.replace(R.id.fragmentContainer, fragment, TAG)
					.addToBackStack(TAG)
					.commitAllowingStateLoss();
		}
	}
}
