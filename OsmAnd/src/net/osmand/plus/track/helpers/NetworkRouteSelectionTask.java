package net.osmand.plus.track.helpers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import net.osmand.CallbackWithObject;
import net.osmand.GPXUtilities.GPXFile;
import net.osmand.PlatformUtil;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.data.QuadRect;
import net.osmand.plus.base.BaseLoadAsyncTask;
import net.osmand.router.network.NetworkRouteSelector;
import net.osmand.router.network.NetworkRouteSelector.NetworkRouteSelectorFilter;
import net.osmand.router.network.NetworkRouteSelector.RouteKey;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

public class NetworkRouteSelectionTask extends BaseLoadAsyncTask<Void, Void, GPXFile> {

	private static final Log log = PlatformUtil.getLog(NetworkRouteSelectionTask.class);

	private final QuadRect quadRect;
	private final RouteKey routeKey;
	private final CallbackWithObject<GPXFile> callback;

	public NetworkRouteSelectionTask(@NonNull FragmentActivity activity,
	                                 @NonNull RouteKey routeKey,
	                                 @NonNull QuadRect quadRect,
	                                 @Nullable CallbackWithObject<GPXFile> callback) {
		super(activity);
		this.routeKey = routeKey;
		this.quadRect = quadRect;
		this.callback = callback;
	}

	@Override
	protected void onPreExecute() {
		showProgress(true);
	}

	@Override
	protected GPXFile doInBackground(Void... voids) {
		BinaryMapIndexReader[] readers = app.getResourceManager().getReverseGeocodingMapFiles();
		NetworkRouteSelectorFilter selectorFilter = new NetworkRouteSelectorFilter();
		NetworkRouteSelector routeSelector = new NetworkRouteSelector(
				readers, selectorFilter, this::isCancelled);
		RouteKey routeKey = this.routeKey;
		if (routeKey != null) {
			selectorFilter.keyFilter = Collections.singleton(routeKey);
			try {
				Map<RouteKey, GPXFile> routes = routeSelector.getRoutes(quadRect, true, routeKey);
				if (isCancelled()) {
					routes.clear();
				}
				if (!Algorithms.isEmpty(routes)) {
					return routes.values().iterator().next();
				}
			} catch (IOException e) {
				log.error(e);
			}
		}
		return null;
	}

	@Override
	protected void onPostExecute(GPXFile gpxFile) {
		if (callback != null) {
			callback.processResult(gpxFile);
		}
		hideProgress();
	}
}
