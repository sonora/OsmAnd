package net.osmand.plus.myplaces.tracks.tasks;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import net.osmand.CallbackWithObject;
import net.osmand.plus.base.BaseLoadAsyncTask;
import net.osmand.plus.configmap.tracks.TrackItem;
import net.osmand.plus.track.GpxSplitType;
import net.osmand.plus.track.TrackDrawInfo;
import net.osmand.plus.track.helpers.GPXDatabase.GpxDataItem;
import net.osmand.plus.track.helpers.GpxDbHelper;
import net.osmand.plus.track.helpers.GpxDbHelper.GpxDataItemCallback;
import net.osmand.plus.track.helpers.GpxSelectionHelper;
import net.osmand.plus.track.helpers.SelectedGpxFile;

import java.io.File;
import java.util.Set;

public class ChangeTracksAppearanceTask extends BaseLoadAsyncTask<Void, File, Void> {

	private final GpxDbHelper gpxDbHelper;
	private final GpxSelectionHelper selectionHelper;

	private final Set<TrackItem> trackItems;
	private final TrackDrawInfo trackDrawInfo;
	private final CallbackWithObject<Void> callback;

	public ChangeTracksAppearanceTask(@NonNull FragmentActivity activity,
	                                  @NonNull TrackDrawInfo trackDrawInfo,
	                                  @NonNull Set<TrackItem> trackItems,
	                                  @Nullable CallbackWithObject<Void> callback) {
		super(activity);
		this.trackItems = trackItems;
		this.trackDrawInfo = trackDrawInfo;
		this.callback = callback;
		this.gpxDbHelper = app.getGpxDbHelper();
		this.selectionHelper = app.getSelectedGpxHelper();
	}

	@Override
	protected Void doInBackground(Void... params) {
		GpxDataItemCallback callback = getGpxDataItemCallback();
		for (TrackItem trackItem : trackItems) {
			File file = trackItem.getFile();
			if (file != null) {
				GpxDataItem item = gpxDbHelper.getItem(file, callback);
				if (item != null) {
					updateTrackAppearance(item);
				}
			} else if (trackItem.isShowCurrentTrack()) {
				updateCurrentTrackAppearance();
			}
		}
		return null;
	}

	private void updateTrackAppearance(@NonNull GpxDataItem item) {
		int color = trackDrawInfo.getColor();
		String width = trackDrawInfo.getWidth();
		boolean showArrows = trackDrawInfo.isShowArrows();
		boolean showStartFinish = trackDrawInfo.isShowStartFinish();
		int splitType = GpxSplitType.getSplitTypeByTypeId(trackDrawInfo.getSplitType()).getType();
		double splitInterval = trackDrawInfo.getSplitInterval();
		String coloringType = trackDrawInfo.getColoringTypeName();

		gpxDbHelper.updateAppearance(item, color, width, showArrows, showStartFinish, splitType, splitInterval, coloringType);

		SelectedGpxFile selectedGpxFile = selectionHelper.getSelectedFileByPath(item.getFile().getAbsolutePath());
		if (selectedGpxFile != null) {
			selectedGpxFile.resetSplitProcessed();
		}
	}

	private void updateCurrentTrackAppearance() {
		settings.CURRENT_TRACK_COLOR.set(trackDrawInfo.getColor());
		settings.CURRENT_TRACK_COLORING_TYPE.set(trackDrawInfo.getColoringType());
		settings.CURRENT_TRACK_ROUTE_INFO_ATTRIBUTE.set(trackDrawInfo.getRouteInfoAttribute());
		settings.CURRENT_TRACK_WIDTH.set(trackDrawInfo.getWidth());
		settings.CURRENT_TRACK_SHOW_ARROWS.set(trackDrawInfo.isShowArrows());
		settings.CURRENT_TRACK_SHOW_START_FINISH.set(trackDrawInfo.isShowStartFinish());
	}

	@NonNull
	private GpxDataItemCallback getGpxDataItemCallback() {
		return new GpxDataItemCallback() {
			@Override
			public boolean isCancelled() {
				return ChangeTracksAppearanceTask.this.isCancelled();
			}

			@Override
			public void onGpxDataItemReady(@NonNull GpxDataItem item) {
				updateTrackAppearance(item);
			}
		};
	}

	@Override
	protected void onPostExecute(Void result) {
		hideProgress();

		if (callback != null) {
			callback.processResult(null);
		}
	}
}

