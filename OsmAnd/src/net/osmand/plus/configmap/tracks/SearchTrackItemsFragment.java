package net.osmand.plus.configmap.tracks;

import static net.osmand.plus.utils.UiUtilities.DialogButtonType.TERTIARY;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.osmand.Location;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmAndLocationProvider.OsmAndCompassListener;
import net.osmand.plus.OsmAndLocationProvider.OsmAndLocationListener;
import net.osmand.plus.R;
import net.osmand.plus.base.BaseOsmAndDialogFragment;
import net.osmand.plus.configmap.tracks.viewholders.EmptyTracksViewHolder.ImportTracksListener;
import net.osmand.plus.configmap.tracks.viewholders.SortTracksViewHolder.SortTracksListener;
import net.osmand.plus.configmap.tracks.viewholders.TrackViewHolder.TrackSelectionListener;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.myplaces.tracks.ItemsSelectionHelper;
import net.osmand.plus.settings.enums.TracksSortMode;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.UiUtilities;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SearchTrackItemsFragment extends BaseOsmAndDialogFragment implements OsmAndCompassListener,
		OsmAndLocationListener, TrackItemsContainer, SortTracksListener {

	public static final String TAG = SearchTrackItemsFragment.class.getSimpleName();

	private ItemsSelectionHelper<TrackItem> selectionHelper;

	private SearchTracksAdapter adapter;

	private View applyButton;
	private View buttonsContainer;
	private View selectionButton;
	private View clearSearchQuery;
	private EditText searchEditText;

	private Location location;
	private Float heading;
	private boolean locationUpdateStarted;
	private boolean compassUpdateAllowed = true;

	@Override
	protected boolean useMapNightMode() {
		return true;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Fragment fragment = requireParentFragment();
		if (fragment instanceof TracksFragment) {
			TracksFragment tracksFragment = (TracksFragment) fragment;
			selectionHelper = tracksFragment.getItemsSelectionHelper();
		}
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		inflater = UiUtilities.getInflater(requireContext(), nightMode);
		View view = inflater.inflate(R.layout.gpx_search_items_fragment, container, false);
		view.setBackgroundColor(ContextCompat.getColor(app, nightMode ? R.color.activity_background_color_dark : R.color.list_background_color_light));

		Fragment fragment = requireParentFragment();
		List<TrackItem> trackItems = new ArrayList<>(selectionHelper.getAllItems());
		adapter = new SearchTracksAdapter(app, trackItems, nightMode);
		adapter.setTracksSortMode(getTracksSortMode());
		adapter.setSortTracksListener(this);
		adapter.setFilterCallback(filteredItems -> {
			adapter.updateFilteredItems(filteredItems);
			updateButtonsState();
			return true;
		});
		if (fragment instanceof TrackSelectionListener) {
			adapter.setSelectionListener((TrackSelectionListener) fragment);
		}
		if (fragment instanceof ImportTracksListener) {
			adapter.setImportTracksListener((ImportTracksListener) fragment);
		}

		RecyclerView recyclerView = view.findViewById(R.id.recycler_view);
		recyclerView.setLayoutManager(new LinearLayoutManager(app));
		recyclerView.setItemAnimator(null);
		recyclerView.setAdapter(adapter);
		recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
				super.onScrollStateChanged(recyclerView, newState);
				compassUpdateAllowed = newState == RecyclerView.SCROLL_STATE_IDLE;
			}
		});

		setupToolbar(view);
		setupSearch(view);
		setupButtons(view);
		return view;
	}

	@Override
	public void onResume() {
		super.onResume();
		searchEditText.requestFocus();
		AndroidUtils.showSoftKeyboard(requireActivity(), searchEditText);
		startLocationUpdate();
	}

	private void setupButtons(@NonNull View view) {
		buttonsContainer = view.findViewById(R.id.buttons_container);
		applyButton = view.findViewById(R.id.apply_button);
		applyButton.setOnClickListener(v -> saveChanges());

		selectionButton = view.findViewById(R.id.selection_button);
		selectionButton.setOnClickListener(v -> {
			Set<TrackItem> items = new HashSet<>(adapter.getFilteredItems());
			selectionHelper.onItemsSelected(items, !areAllTracksSelected());
			onTrackItemsSelected(items);
		});
		updateButtonsState();
	}

	private void saveChanges() {
		Fragment fragment = requireParentFragment();
		if (fragment instanceof TracksFragment) {
			TracksFragment tracksFragment = (TracksFragment) fragment;
			tracksFragment.saveChanges();
			tracksFragment.updateTabsContent();
		}
		dismissAllowingStateLoss();
	}

	private boolean areAllTracksSelected() {
		List<TrackItem> filteredItems = adapter.getFilteredItems();
		int selectedTracksCount = 0;
		for (TrackItem item : filteredItems) {
			if (selectionHelper.isItemSelected(item)) {
				selectedTracksCount++;
			}
		}
		return selectedTracksCount == filteredItems.size();
	}

	private void updateButtonsState() {
		String apply = getString(R.string.shared_string_apply).toUpperCase();
		String select = getString(!areAllTracksSelected() ? R.string.shared_string_select_all : R.string.shared_string_deselect_all).toUpperCase();
		String count = "(" + adapter.getFilteredItems().size() + ")";
		select = getString(R.string.ltr_or_rtl_combine_via_space, select, count);
		applyButton.setEnabled(selectionHelper.hasItemsToApply());
		UiUtilities.setupDialogButton(nightMode, applyButton, TERTIARY, apply);
		UiUtilities.setupDialogButton(nightMode, selectionButton, TERTIARY, select);

		boolean visible = adapter.getFilteredItems().size() > 0;
		AndroidUiHelper.updateVisibility(buttonsContainer, visible);
	}

	private void setupToolbar(@NonNull View view) {
		View appbar = view.findViewById(R.id.appbar);
		ViewCompat.setElevation(appbar, 5.0f);
		appbar.setBackgroundColor(ContextCompat.getColor(app, nightMode ? R.color.app_bar_color_dark : R.color.app_bar_color_light));
		setStatusBarBackgroundColor(ContextCompat.getColor(app, nightMode ? R.color.status_bar_main_dark : R.color.status_bar_main_light));
	}

	private void setStatusBarBackgroundColor(@ColorRes int color) {
		Window window = requireDialog().getWindow();
		if (window != null) {
			AndroidUiHelper.setStatusBarContentColor(window.getDecorView(), true);
			window.setStatusBarColor(color);
		}
	}

	private void setupSearch(@NonNull View view) {
		View searchContainer = view.findViewById(R.id.search_container);
		clearSearchQuery = searchContainer.findViewById(R.id.clearButton);
		clearSearchQuery.setVisibility(View.GONE);
		ImageButton backButton = view.findViewById(R.id.back_button);
		backButton.setVisibility(View.VISIBLE);
		backButton.setOnClickListener((v) -> dismiss());
		searchEditText = searchContainer.findViewById(R.id.searchEditText);
		searchEditText.setHint(R.string.search_track_by_name);
		searchEditText.setTextColor(ContextCompat.getColor(app, R.color.color_white));
		searchEditText.setHintTextColor(ContextCompat.getColor(app, R.color.white_50_transparent));
		searchEditText.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable query) {
				filterTracks(query.toString());
				AndroidUiHelper.updateVisibility(clearSearchQuery, query.length() > 0);
			}
		});
		clearSearchQuery.setOnClickListener((v) -> resetSearchQuery());
	}

	private void resetSearchQuery() {
		filterTracks(null);
		searchEditText.setText(null);
	}

	private void filterTracks(@Nullable String query) {
		adapter.getFilter().filter(query);
	}

	@Override
	public void onTrackItemsSelected(@NonNull Set<TrackItem> trackItems) {
		adapter.notifyDataSetChanged();
		updateButtonsState();
	}

	@Override
	public void updateContent() {
		adapter.notifyDataSetChanged();
		updateButtonsState();
		updateLocationUi();
	}

	@Override
	public void onPause() {
		super.onPause();
		stopLocationUpdate();
	}

	@Override
	public void updateLocation(Location location) {
		if (!MapUtils.areLatLonEqual(this.location, location)) {
			this.location = location;
			updateLocationUi();
		}
	}

	@Override
	public void updateCompassValue(float value) {
		// 99 in next line used to one-time initialize arrows (with reference vs. fixed-north direction)
		// on non-compass devices
		float lastHeading = heading != null ? heading : 99;
		heading = value;
		if (Math.abs(MapUtils.degreesDiff(lastHeading, heading)) > 5) {
			updateLocationUi();
		} else {
			heading = lastHeading;
		}
	}

	private void updateLocationUi() {
		if (compassUpdateAllowed && adapter != null) {
			app.runInUIThread(() -> {
				if (location == null) {
					location = app.getLocationProvider().getLastKnownLocation();
				}
				adapter.notifyDataSetChanged();
			});
		}
	}

	public void startLocationUpdate() {
		if (!locationUpdateStarted) {
			locationUpdateStarted = true;
			OsmAndLocationProvider locationProvider = app.getLocationProvider();
			locationProvider.removeCompassListener(locationProvider.getNavigationInfo());
			locationProvider.addCompassListener(this);
			locationProvider.addLocationListener(this);
			updateLocationUi();
		}
	}

	public void stopLocationUpdate() {
		if (locationUpdateStarted) {
			locationUpdateStarted = false;
			OsmAndLocationProvider locationProvider = app.getLocationProvider();
			locationProvider.removeLocationListener(this);
			locationProvider.removeCompassListener(this);
			locationProvider.addCompassListener(locationProvider.getNavigationInfo());
		}
	}

	@Override
	public void showSortByDialog() {
		FragmentManager manager = getFragmentManager();
		if (manager != null) {
			SortByBottomSheet.showInstance(manager, this);
		}
	}

	@Override
	public void setTracksSortMode(@NonNull TracksSortMode sortMode) {
		adapter.setTracksSortMode(sortMode);
		adapter.notifyDataSetChanged();

		Fragment fragment = requireParentFragment();
		if (fragment instanceof SortTracksListener) {
			((SortTracksListener) fragment).setTracksSortMode(sortMode);
		}
	}

	@NonNull
	@Override
	public TracksSortMode getTracksSortMode() {
		Fragment fragment = requireParentFragment();
		if (fragment instanceof SortTracksListener) {
			return ((SortTracksListener) fragment).getTracksSortMode();
		}
		return TracksSortMode.getDefaultSortMode();
	}

	public static void showInstance(@NonNull FragmentManager manager) {
		if (AndroidUtils.isFragmentCanBeAdded(manager, TAG)) {
			SearchTrackItemsFragment fragment = new SearchTrackItemsFragment();
			fragment.show(manager, TAG);
		}
	}
}
