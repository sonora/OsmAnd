package net.osmand.plus.views.layers;

import static android.graphics.Paint.ANTI_ALIAS_FLAG;
import static android.graphics.Paint.FILTER_BITMAP_FLAG;
import static net.osmand.plus.views.AnimateMapMarkersThread.ROTATE_ANIMATION_TIME;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.core.android.MapRendererView;
import net.osmand.core.jni.AnimatedValue;
import net.osmand.core.jni.MapMarker;
import net.osmand.core.jni.MapMarkerBuilder;
import net.osmand.core.jni.MapMarkersCollection;
import net.osmand.core.jni.PointI;
import net.osmand.core.jni.SWIGTYPE_p_void;
import net.osmand.core.jni.SwigUtilities;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmAndLocationProvider.OsmAndCompassListener;
import net.osmand.plus.OsmAndLocationProvider.OsmAndLocationListener;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.base.MapViewTrackingUtilities;
import net.osmand.plus.profiles.ProfileIconColors;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.utils.NativeUtilities;
import net.osmand.plus.views.AnimateMapMarkersThread;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.layers.ContextMenuLayer.IContextMenuProvider;
import net.osmand.plus.views.layers.base.OsmandMapLayer;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.util.List;

public class PointLocationLayer extends OsmandMapLayer implements OsmAndLocationListener,
		OsmAndCompassListener, IContextMenuProvider {
	private static final Log LOG = PlatformUtil.getLog(PointLocationLayer.class);

	protected static final float BEARING_SPEED_THRESHOLD = 0.1f;
	protected static final int MIN_ZOOM = 3;
	protected static final int RADIUS = 7;

	private Paint headingPaint;
	private Paint bitmapPaint;
	private Paint area;
	private Paint aroundArea;

	private ApplicationMode appMode;
	private boolean carView;
	private float textScale = 1f;
	@ColorInt
	private int profileColor;
	private LayerDrawable navigationIcon;
	private int navigationIconId;
	private LayerDrawable locationIcon;
	private int locationIconId;
	private Bitmap headingIcon;
	private int headingIconId;
	private final OsmAndLocationProvider locationProvider;
	private final MapViewTrackingUtilities mapViewTrackingUtilities;
	private final OsmandSettings settings;
	private boolean nm;
	private boolean locationOutdated;
	private Location prevLocation;

	private static final int MARKER_ID_MY_LOCATION = 1;
	private static final int MARKER_ID_NAVIGATION = 2;
	private static final int MARKER_ID_MY_LOCATION_HEADING = 3;
	private static final int MARKER_ID_NAVIGATION_HEADING = 4;
	private CoreMapMarker locationMarker;
	private CoreMapMarker locationMarkerWithHeading;
	private CoreMapMarker navigationMarker;
	private CoreMapMarker navigationMarkerWithHeading;

	private boolean markersInvalidated = true;
	private boolean showHeadingCached = false;
	private Float lastBearingCached;
	private Float lastHeadingCached;
	private MarkerState currentMarkerState = MarkerState.Stay;
	private LatLon lastMarkerLocation;
	private boolean movingToMyLocationCached;

	private enum MarkerState {
		Stay,
		Move,
		None,
	}

	private static class CoreMapMarker {
		private MapMarker marker;
		private SWIGTYPE_p_void onSurfaceIconKey;
		private SWIGTYPE_p_void onSurfaceHeadingIconKey;

		public static CoreMapMarker createAndAddToCollection(@NonNull Context ctx, @NonNull MapMarkersCollection markersCollection,
		                                                     int id, int baseOrder, @NonNull Drawable icon, @DrawableRes int headingIconId,
		                                                     float scale, @ColorInt int profileColor, boolean withHeading) {
			CoreMapMarker marker = new CoreMapMarker();
			MapMarkerBuilder myLocMarkerBuilder = new MapMarkerBuilder();
			myLocMarkerBuilder.setMarkerId(id);
			myLocMarkerBuilder.setBaseOrder(baseOrder);
			myLocMarkerBuilder.setIsAccuracyCircleSupported(true);
			myLocMarkerBuilder.setAccuracyCircleBaseColor(NativeUtilities.createFColorRGB(profileColor));
			myLocMarkerBuilder.setPinIconVerticalAlignment(MapMarker.PinIconVerticalAlignment.CenterVertical);
			myLocMarkerBuilder.setPinIconHorisontalAlignment(MapMarker.PinIconHorisontalAlignment.CenterHorizontal);
			myLocMarkerBuilder.setIsHidden(true);

			Bitmap markerBitmap = AndroidUtils.createScaledBitmap(icon, scale);
			if (markerBitmap != null) {
				marker.onSurfaceIconKey = SwigUtilities.getOnSurfaceIconKey(1);
				myLocMarkerBuilder.addOnMapSurfaceIcon(marker.onSurfaceIconKey,
						NativeUtilities.createSkImageFromBitmap(markerBitmap));
			}

			if (withHeading) {
				Bitmap headingBitmap = AndroidUtils.createScaledBitmapWithTint(ctx, headingIconId, scale, profileColor);
				if (headingBitmap != null) {
					marker.onSurfaceHeadingIconKey = SwigUtilities.getOnSurfaceIconKey(2);
					myLocMarkerBuilder.addOnMapSurfaceIcon(marker.onSurfaceHeadingIconKey,
							NativeUtilities.createSkImageFromBitmap(headingBitmap));
				}
			}
			marker.marker = myLocMarkerBuilder.buildAndAddToCollection(markersCollection);
			return marker.marker != null ? marker : null;
		}

		public void setVisibility(boolean visible) {
			if (marker == null) {
				return;
			}
			marker.setIsHidden(!visible);
			marker.setIsAccuracyCircleVisible(visible);
		}
	}

	public PointLocationLayer(@NonNull Context context) {
		super(context);
		this.mapViewTrackingUtilities = getApplication().getMapViewTrackingUtilities();
		this.locationProvider = getApplication().getLocationProvider();
		this.settings = getApplication().getSettings();
	}

	private void initLegacyRenderer() {
		headingPaint = new Paint(ANTI_ALIAS_FLAG | FILTER_BITMAP_FLAG);
		bitmapPaint = new Paint(ANTI_ALIAS_FLAG | FILTER_BITMAP_FLAG);
		area = new Paint();
		aroundArea = new Paint();
		aroundArea.setStyle(Style.STROKE);
		aroundArea.setStrokeWidth(1);
		aroundArea.setAntiAlias(true);
	}

	private void initCoreRenderer() {
		markersInvalidated = true;
	}

	@Override
	public void setMapActivity(@Nullable MapActivity mapActivity) {
		super.setMapActivity(mapActivity);
		if (mapActivity != null) {
			initCoreRenderer();
		} else {
			clearMapMarkersCollections();
		}
	}

	@Override
	public void initLayer(@NonNull OsmandMapTileView view) {
		super.initLayer(view);
		boolean hasMapRenderer = hasMapRenderer();
		if (hasMapRenderer) {
			initCoreRenderer();
		} else {
			initLegacyRenderer();
		}
		updateParams(view.getSettings().getApplicationMode(), false, locationProvider.getLastKnownLocation() == null);
		locationProvider.addLocationListener(this);
		locationProvider.addCompassListener(this);
	}

	public LatLon getLastMarkerLocation() {
		return lastMarkerLocation;
	}

	@Override
	public boolean areMapRendererViewEventsAllowed() {
		return true;
	}

	@Override
	public void onUpdateFrame(MapRendererView mapRenderer) {
		super.onUpdateFrame(mapRenderer);
		if (isMapLinkedToLocation()) {
			OsmandApplication app = getApplication();
			Location lastKnownLocation = locationProvider.getLastStaleKnownLocation();
			Boolean snapToRoad = app.getSettings().SNAP_TO_ROAD.get();
			boolean followingMode = app.getRoutingHelper().isFollowingMode();
			Location lastRouteProjection = followingMode && snapToRoad
					? app.getOsmandMap().getMapLayers().getRouteLayer().getLastRouteProjection() : null;
			PointI target31 = mapRenderer.getTarget();
			boolean movingToMyLocation = mapViewTrackingUtilities.isMovingToMyLocation();
			boolean useLocation = false;//movingToMyLocation || !movingToMyLocationCached;
			Location location = lastRouteProjection != null ? lastRouteProjection : lastKnownLocation;
			updateMarker(location, useLocation ? null : target31, 0);
			if (movingToMyLocation) {
				movingToMyLocationCached = true;
			}
		} else {
			movingToMyLocationCached = false;
		}
		lastMarkerLocation = getCurrentMarkerLocation();
	}

	private boolean setMarkerState(MarkerState markerState, boolean showHeading, boolean forceUpdate) {
		if (currentMarkerState == markerState && !forceUpdate) {
			return false;
		}
		currentMarkerState = markerState;
		updateMarkerState(showHeading);
		return true;
	}

	@Nullable
	private CoreMapMarker recreateMarker(Drawable icon, int id, @ColorInt int profileColor, boolean withHeading) {
		if (view == null || icon == null) {
			return null;
		}
		if (mapMarkersCollection == null) {
			mapMarkersCollection = new MapMarkersCollection();
		}
		return CoreMapMarker.createAndAddToCollection(getContext(), mapMarkersCollection, id,
				getPointsOrder(), icon, headingIconId, getTextScale(), profileColor, withHeading);
	}

	private void setMarkerProvider() {
		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer != null && mapMarkersCollection != null) {
			mapRenderer.addSymbolsProvider(mapMarkersCollection);
		}
	}

	private boolean recreateMarkerCollection() {
		if (view == null || !hasMapRenderer()) {
			return false;
		}
		clearMapMarkersCollections();
		locationMarker = recreateMarker(locationIcon, MARKER_ID_MY_LOCATION, profileColor, false);
		locationMarkerWithHeading = recreateMarker(locationIcon, MARKER_ID_MY_LOCATION_HEADING, profileColor, true);
		navigationMarker = recreateMarker(navigationIcon, MARKER_ID_NAVIGATION, profileColor, false);
		navigationMarkerWithHeading = recreateMarker(navigationIcon, MARKER_ID_NAVIGATION_HEADING, profileColor, true);
		setMarkerProvider();
		return true;
	}

	private void updateMarkerState(boolean showHeading) {
		if (navigationMarker == null || locationMarker == null
				|| navigationMarkerWithHeading == null || locationMarkerWithHeading == null) {
			return;
		}
		switch (currentMarkerState) {
			case Move:
				navigationMarker.setVisibility(!showHeading);
				locationMarker.setVisibility(false);
				navigationMarkerWithHeading.setVisibility(showHeading);
				locationMarkerWithHeading.setVisibility(false);
				break;
			case Stay:
				navigationMarker.setVisibility(false);
				locationMarker.setVisibility(!showHeading);
				navigationMarkerWithHeading.setVisibility(false);
				locationMarkerWithHeading.setVisibility(showHeading);
				break;
			case None:
			default:
				navigationMarker.setVisibility(false);
				locationMarker.setVisibility(false);
				navigationMarkerWithHeading.setVisibility(false);
				locationMarkerWithHeading.setVisibility(false);
		}
	}

	@Nullable
	private CoreMapMarker getCurrentMarker() {
		CoreMapMarker locMarker;
		boolean showHeading = showHeadingCached;
		switch (currentMarkerState) {
			case Move:
				locMarker = showHeading ? navigationMarkerWithHeading : navigationMarker;
				break;
			case Stay:
				locMarker = showHeading ? locationMarkerWithHeading : locationMarker;
				break;
			case None:
			default:
				return null;
		}
		return locMarker;
	}

	private void updateMarker(@Nullable Location location, @Nullable PointI target31, long animationDuration) {
		Float heading = locationProvider.getHeading();
		if (location != null) {
			updateMarkerPosition(location, target31, animationDuration);
			if (location.hasBearing()) {
				float bearing = location.getBearing() - 90.0f;
				boolean updateBearing = lastBearingCached == null || Math.abs(bearing - lastBearingCached) > 0.1;
				if (updateBearing) {
					lastBearingCached = bearing;
					updateMarkerBearing(bearing, isAnimateMyLocation());
				}
			}
		}
		if (heading != null && showHeadingCached) {
			boolean updateHeading = lastHeadingCached == null || Math.abs(heading - lastHeadingCached) > 0.1;
			if (updateHeading) {
				lastHeadingCached = heading;
				updateMarkerHeading(heading);
			}
		} else {
			lastHeadingCached = null;
		}
	}

	private void updateMarkerPosition(@NonNull Location location, @Nullable PointI target31, long animationDuration) {
		CoreMapMarker locMarker = getCurrentMarker();
		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer != null && view != null && locMarker != null && locMarker.marker != null) {
			if (target31 == null) {
				target31 = new PointI(MapUtils.get31TileNumberX(location.getLongitude()),
						MapUtils.get31TileNumberY(location.getLatitude()));
			}
			AnimateMapMarkersThread animationThread = view.getAnimatedMapMarkersThread();
			animationThread.cancelCurrentAnimation(locMarker.marker, AnimatedValue.Target);
			if (animationDuration > 0) {
				animationThread.animatePositionTo(locMarker.marker, target31, animationDuration);
			} else {
				locMarker.marker.setPosition(target31);
			}
			locMarker.marker.setAccuracyCircleRadius(location.getAccuracy());
		}
	}

	private void updateMarkerBearing(float bearing, boolean animateRotation) {
		MapRendererView mapRenderer = getMapRenderer();
		CoreMapMarker locMarker = getCurrentMarker();
		if (mapRenderer != null && view != null && locMarker != null && locMarker.marker != null) {
			AnimateMapMarkersThread animationThread = view.getAnimatedMapMarkersThread();
			animationThread.cancelCurrentAnimation(locMarker.marker, AnimatedValue.Azimuth);
			long bearingRotationDuration = animateRotation && locMarker.onSurfaceIconKey != null
					? ROTATE_ANIMATION_TIME : 0;
			if (bearingRotationDuration > 0) {
				animationThread.animateDirectionTo(locMarker.marker, locMarker.onSurfaceIconKey,
						bearing, bearingRotationDuration);
			} else if (locMarker.onSurfaceIconKey != null) {
				locMarker.marker.setOnMapSurfaceIconDirection(locMarker.onSurfaceIconKey, bearing);
			}
		}
	}

	private void updateMarkerHeading(float heading) {
		MapRendererView mapRenderer = getMapRenderer();
		CoreMapMarker locMarker = getCurrentMarker();
		if (mapRenderer != null && view != null && locMarker != null && locMarker.marker != null) {
			if (locMarker.onSurfaceHeadingIconKey != null) {
				locMarker.marker.setOnMapSurfaceIconDirection(locMarker.onSurfaceHeadingIconKey, heading);
			}
		}
	}

	@Nullable
	private PointI getCurrentMarkerPosition() {
		CoreMapMarker locMarker;
		boolean showHeading = showHeadingCached;
		switch (currentMarkerState) {
			case Move:
				locMarker = showHeading ? navigationMarkerWithHeading : navigationMarker;
				break;
			case Stay:
				locMarker = showHeading ? locationMarkerWithHeading : locationMarker;
				break;
			case None:
			default:
				return null;
		}
		return locMarker != null && locMarker.marker != null ? locMarker.marker.getPosition() : null;
	}

	@Nullable
	private LatLon getCurrentMarkerLocation() {
		PointI pos31 = getCurrentMarkerPosition();
		return pos31 != null
				? new LatLon(MapUtils.get31LatitudeY(pos31.getY()), MapUtils.get31LongitudeX(pos31.getX()))
				: null;
	}

	private boolean shouldShowHeading() {
		return !locationOutdated && mapViewTrackingUtilities.isShowViewAngle();
	}

	private boolean shouldShowBearing(@Nullable Location location) {
		return !locationOutdated && location != null
				// Issue 5538: Some devices return positives for hasBearing() at rest, hence add 0.0 check:
				&& location.hasBearing() && (location.getBearing() != 0.0f)
				&& (!location.hasSpeed() || location.getSpeed() > BEARING_SPEED_THRESHOLD);
	}

	private boolean isLocationVisible(@NonNull RotatedTileBox tb, @NonNull Location l) {
		return tb.containsLatLon(l.getLatitude(), l.getLongitude());
	}

	private void drawMarkers(@NonNull Canvas canvas, @NonNull RotatedTileBox box, @NonNull Location lastKnownLocation) {
		int locationX;
		int locationY;
		if (mapViewTrackingUtilities.isMapLinkedToLocation()
				&& !MapViewTrackingUtilities.isSmallSpeedForAnimation(lastKnownLocation)
				&& !mapViewTrackingUtilities.isMovingToMyLocation()) {
			locationX = box.getCenterPixelX();
			locationY = box.getCenterPixelY();
		} else {
			locationX = box.getPixXFromLonNoRot(lastKnownLocation.getLongitude());
			locationY = box.getPixYFromLatNoRot(lastKnownLocation.getLatitude());
		}

		double dist = box.getDistance(0, box.getPixHeight() / 2, box.getPixWidth(), box.getPixHeight() / 2);
		int radius = (int) (((double) box.getPixWidth()) / dist * lastKnownLocation.getAccuracy());
		if (radius > RADIUS * box.getDensity()) {
			int allowedRad = Math.min(box.getPixWidth() / 2, box.getPixHeight() / 2);
			canvas.drawCircle(locationX, locationY, Math.min(radius, allowedRad), area);
			canvas.drawCircle(locationX, locationY, Math.min(radius, allowedRad), aroundArea);
		}
		// draw bearing/direction/location
		if (isLocationVisible(box, lastKnownLocation)) {
			Float heading = locationProvider.getHeading();
			if (shouldShowHeading() && heading != null) {
				canvas.save();
				canvas.rotate(heading - 180, locationX, locationY);
				canvas.drawBitmap(headingIcon, locationX - headingIcon.getWidth() / 2f,
						locationY - headingIcon.getHeight() / 2f, headingPaint);
				canvas.restore();
			}
			if (shouldShowBearing(lastKnownLocation)) {
				float bearing = lastKnownLocation.getBearing();
				canvas.rotate(bearing - 90, locationX, locationY);
				int width = (int) (navigationIcon.getIntrinsicWidth() * textScale);
				int height = (int) (navigationIcon.getIntrinsicHeight() * textScale);
				width += width % 2 == 1 ? 1 : 0;
				height += height % 2 == 1 ? 1 : 0;
				if (textScale == 1) {
					navigationIcon.setBounds(locationX - width / 2, locationY - height / 2,
							locationX + width / 2, locationY + height / 2);
					navigationIcon.draw(canvas);
				} else {
					navigationIcon.setBounds(0, 0, width, height);
					Bitmap bitmap = AndroidUtils.createScaledBitmap(navigationIcon, width, height);
					canvas.drawBitmap(bitmap, locationX - width / 2f, locationY - height / 2f, bitmapPaint);
				}
			} else {
				int width = (int) (locationIcon.getIntrinsicWidth() * textScale);
				int height = (int) (locationIcon.getIntrinsicHeight() * textScale);
				width += width % 2 == 1 ? 1 : 0;
				height += height % 2 == 1 ? 1 : 0;
				if (textScale == 1) {
					locationIcon.setBounds(locationX - width / 2, locationY - height / 2,
							locationX + width / 2, locationY + height / 2);
					locationIcon.draw(canvas);
				} else {
					locationIcon.setBounds(0, 0, width, height);
					Bitmap bitmap = AndroidUtils.createScaledBitmap(locationIcon, width, height);
					canvas.drawBitmap(bitmap, locationX - width / 2f, locationY - height / 2f, bitmapPaint);
				}
			}
		}
	}

	@Override
	public void onPrepareBufferImage(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		super.onPrepareBufferImage(canvas, tileBox, settings);
		Location lastKnownLocation = locationProvider.getLastStaleKnownLocation();
		if (view == null || tileBox.getZoom() < MIN_ZOOM || lastKnownLocation == null) {
			clearMapMarkersCollections();
			return;
		}
		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer != null) {
			boolean markersRecreated = false;
			if (markersInvalidated || mapMarkersCollection == null) {
				markersRecreated = recreateMarkerCollection();
				markersInvalidated = false;
			}
			boolean showHeading = shouldShowHeading();
			boolean showBearing = shouldShowBearing(lastKnownLocation);
			boolean stateUpdated = setMarkerState(showBearing ?
					MarkerState.Move : MarkerState.Stay, showHeading, markersRecreated);
			if (showHeading != showHeadingCached) {
				showHeadingCached = showHeading;
				if (!stateUpdated) {
					updateMarkerState(showHeading);
					stateUpdated = true;
				}
			}
			if ((markersRecreated || stateUpdated) && !isMapLinkedToLocation()) {
				lastBearingCached = null;
				lastHeadingCached = null;
				updateMarker(lastKnownLocation, null, 0);
			}
		}
		boolean nightMode = settings != null && settings.isNightMode();
		updateParams(view.getSettings().getApplicationMode(), nightMode, locationProvider.getLastKnownLocation() == null);
	}

	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		Location lastKnownLocation = locationProvider.getLastStaleKnownLocation();
		if (view == null || tileBox.getZoom() < MIN_ZOOM || lastKnownLocation == null) {
			return;
		}
		if (!hasMapRenderer()) {
			drawMarkers(canvas, tileBox, lastKnownLocation);
		}
	}

	@Override
	public void destroyLayer() {
		super.destroyLayer();
		locationProvider.removeLocationListener(this);
		locationProvider.removeCompassListener(this);
		clearMapMarkersCollections();
	}

	@Override
	public void updateLocation(Location location) {
		if (view == null || view.getZoom() < MIN_ZOOM || location == null) {
			return;
		}
		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer != null && !isMapLinkedToLocation()) {
			boolean dataChanged = !MapUtils.areLatLonEqualPrecise(prevLocation, location);
			if (dataChanged) {
				long movingTime = prevLocation != null ? location.getTime() - prevLocation.getTime() : 0;
				updateMarker(location, null, isAnimateMyLocation() ? movingTime : 0);
				prevLocation = location;
			}
		}
	}

	@Override
	public void updateCompassValue(float value) {
		updateMarker(null, null, 0);
	}

	private boolean isAnimateMyLocation() {
		return settings.ANIMATE_MY_LOCATION.get();
	}

	private boolean isMapLinkedToLocation() {
		return mapViewTrackingUtilities.isMapLinkedToLocation();
	}

	private void updateParams(ApplicationMode appMode, boolean nighMode, boolean locationOutdated) {
		Context ctx = getContext();
		int profileColor = locationOutdated ?
				ContextCompat.getColor(ctx, ProfileIconColors.getOutdatedLocationColor(nighMode)) :
				appMode.getProfileColor(nighMode);
		int locationIconId = appMode.getLocationIcon().getIconId();
		int navigationIconId = appMode.getNavigationIcon().getIconId();
		int headingIconId = appMode.getLocationIcon().getHeadingIconId();
		float textScale = getTextScale();
		boolean carView = getApplication().getOsmandMap().getMapView().isCarView();
		if (appMode != this.appMode || this.nm != nighMode || this.locationOutdated != locationOutdated
				|| this.profileColor != profileColor
				|| this.locationIconId != locationIconId
				|| this.headingIconId != headingIconId
				|| this.navigationIconId != navigationIconId
				|| this.textScale != textScale
				|| this.carView != carView) {
			this.appMode = appMode;
			this.profileColor = profileColor;
			this.nm = nighMode;
			this.locationOutdated = locationOutdated;
			this.locationIconId = locationIconId;
			this.headingIconId = headingIconId;
			this.navigationIconId = navigationIconId;
			this.textScale = textScale;
			this.carView = carView;
			navigationIcon = (LayerDrawable) AppCompatResources.getDrawable(ctx, navigationIconId);
			if (navigationIcon != null) {
				DrawableCompat.setTint(navigationIcon.getDrawable(1), profileColor);
			}
			headingIcon = getScaledBitmap(headingIconId);
			locationIcon = (LayerDrawable) AppCompatResources.getDrawable(ctx, locationIconId);
			if (locationIcon != null) {
				DrawableCompat.setTint(DrawableCompat.wrap(locationIcon.getDrawable(1)), profileColor);
			}
			if (!hasMapRenderer()) {
				headingPaint.setColorFilter(new PorterDuffColorFilter(profileColor, PorterDuff.Mode.SRC_IN));
				area.setColor(ColorUtilities.getColorWithAlpha(profileColor, 0.16f));
				aroundArea.setColor(profileColor);
			}
			markersInvalidated = true;
		}
	}

	@Override
	public boolean drawInScreenPixels() {
		return false;
	}

	@Override
	public void collectObjectsFromPoint(PointF point, RotatedTileBox tileBox, List<Object> o, boolean unknownLocation) {
		if (tileBox.getZoom() >= 3) {
			getMyLocationFromPoint(tileBox, point, o);
		}
	}

	@Override
	public LatLon getObjectLocation(Object o) {
		return getMyLocation();
	}

	@Override
	public PointDescription getObjectName(Object o) {
		return new PointDescription(PointDescription.POINT_TYPE_MY_LOCATION,
				getContext().getString(R.string.shared_string_my_location), "");
	}

	@Override
	public boolean disableSingleTap() {
		return false;
	}

	@Override
	public boolean disableLongPressOnMap(PointF point, RotatedTileBox tileBox) {
		return false;
	}

	@Override
	public boolean isObjectClickable(Object o) {
		return false;
	}

	@Override
	public boolean runExclusiveAction(Object o, boolean unknownLocation) {
		return false;
	}

	@Override
	public boolean showMenuAction(@Nullable Object o) {
		return false;
	}

	private LatLon getMyLocation() {
		Location location = locationProvider.getLastKnownLocation();
		if (location != null) {
			return new LatLon(location.getLatitude(), location.getLongitude());
		} else {
			return null;
		}
	}

	private void getMyLocationFromPoint(RotatedTileBox tb, PointF point, List<? super LatLon> myLocation) {
		LatLon location = getMyLocation();
		if (location != null && view != null) {
			int ex = (int) point.x;
			int ey = (int) point.y;
			PointF pixel = NativeUtilities.getPixelFromLatLon(getMapRenderer(), tb,
					location.getLatitude(), location.getLongitude());
			int rad = (int) (18 * tb.getDensity());
			if (Math.abs(pixel.x - ex) <= rad && (ey - pixel.y) <= rad && (pixel.y - ey) <= 2.5 * rad) {
				myLocation.add(location);
			}
		}
	}
}
