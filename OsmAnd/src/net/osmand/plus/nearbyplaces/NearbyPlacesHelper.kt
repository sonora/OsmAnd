package net.osmand.plus.nearbyplaces

import android.os.AsyncTask
import com.squareup.picasso.Picasso
import net.osmand.ResultMatcher
import net.osmand.binary.BinaryMapIndexReader
import net.osmand.binary.ObfConstants
import net.osmand.data.Amenity
import net.osmand.data.LatLon
import net.osmand.data.NearbyPlacePoint
import net.osmand.data.QuadRect
import net.osmand.plus.OsmandApplication
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.search.GetNearbyPlacesImagesTask
import net.osmand.search.core.SearchCoreFactory
import net.osmand.shared.data.KLatLon
import net.osmand.shared.data.KQuadRect
import net.osmand.shared.util.KMapUtils
import net.osmand.util.Algorithms
import net.osmand.util.CollectionUtils
import net.osmand.util.MapUtils
import net.osmand.wiki.WikiCoreHelper.OsmandApiFeatureData
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

object NearbyPlacesHelper {
	private lateinit var app: OsmandApplication
	private var lastModifiedTime: Long = 0
	private const val PLACES_LIMIT = 50000
	private const val NEARBY_MIN_RADIUS: Int = 50

	private var prevMapRect: KQuadRect = KQuadRect()
	private var prevZoom = 0
	private var prevLang = ""

	fun init(app: OsmandApplication) {
		this.app = app
		val loadSavedPlaces = NearbyPlacesLoadSavedTask(app)
		loadSavedPlaces.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
	}

	private var listeners: List<NearbyPlacesListener> = Collections.emptyList()
	private var dataCollection: List<NearbyPlacePoint>? = null

	private val loadNearbyPlacesListener: GetNearbyPlacesImagesTask.GetImageCardsListener =
		object : GetNearbyPlacesImagesTask.GetImageCardsListener {
			override fun onTaskStarted() {
			}

			override fun onFinish(result: List<OsmandApiFeatureData>) {
				dataCollection = result.filter { !Algorithms.isEmpty(it.properties.photoTitle) }
					.map { NearbyPlacePoint(it) }
				dataCollection?.let {
					val newListSize = min(it.size, PLACES_LIMIT)
					dataCollection = it.subList(0, newListSize)
				}
				dataCollection?.let {
					for (point in it) {
						Picasso.get()
							.load(point.iconUrl)
							.fetch()
					}
					SaveNearbyPlacesTask(app, it).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
				}
				updateLastModifiedTime()
				notifyListeners()
			}
		}

	fun addListener(listener: NearbyPlacesListener) {
		listeners = CollectionUtils.addToList(listeners, listener)
	}

	fun removeListener(listener: NearbyPlacesListener) {
		listeners = CollectionUtils.removeFromList(listeners, listener)
	}

	fun notifyListeners() {
		app.runInUIThread {
			for (listener in listeners) {
				listener.onNearbyPlacesUpdated()
			}
		}
	}

	fun getDataCollection(): List<NearbyPlacePoint> {
		return this.dataCollection ?: Collections.emptyList()
	}

	fun onCacheLoaded(cachedPLaces: List<NearbyPlacePoint>) {
		if (!Algorithms.isEmpty(cachedPLaces)) {
			val firstPoint = cachedPLaces[0]
			val qRect = KQuadRect(
				firstPoint.latitude,
				firstPoint.longitude,
				firstPoint.latitude,
				firstPoint.longitude)
			cachedPLaces.forEach { _ ->
				qRect.left = min(firstPoint.latitude, qRect.left)
				qRect.right = max(firstPoint.latitude, qRect.right)
				qRect.top = min(firstPoint.longitude, qRect.top)
				qRect.left = min(firstPoint.longitude, qRect.bottom)
			}
			dataCollection = cachedPLaces
			prevMapRect = qRect
		}
	}

	fun getDataCollection(rect: QuadRect): List<NearbyPlacePoint> {
		val qRect = KQuadRect(rect.left, rect.top, rect.right, rect.bottom)
		val fullCollection = this.dataCollection ?: Collections.emptyList()
		return fullCollection.filter { qRect.contains(KLatLon(it.latitude, it.longitude)) }
	}

	fun startLoadingNearestPhotos() {
		val mapView = app.osmandMap.mapView
		var rect = QuadRect(mapView.currentRotatedTileBox.latLonBounds)
		val qRect = KQuadRect(rect.left, rect.top, rect.right, rect.bottom)
//		var mapRect = calculatedRect
//		mapRect.left = get31LongitudeX(calculatedRect.left.toInt())
//		mapRect.top = get31LatitudeY(calculatedRect.top.toInt())
//		mapRect.right = get31LongitudeX(calculatedRect.right.toInt())
//		mapRect.bottom = get31LatitudeY(calculatedRect.bottom.toInt())
//		Log.d("Corwin", "startLoadingNearestPhotos: ${mapRect}")

		var preferredLang = app.settings.MAP_PREFERRED_LOCALE.get()
		if (Algorithms.isEmpty(preferredLang)) {
			preferredLang = app.language
		}
		if (!prevMapRect.contains(qRect) ||
			prevLang != preferredLang) {
			val mapCenter = mapView.currentRotatedTileBox.centerLatLon
			prevMapRect =
				KMapUtils.calculateLatLonBbox(mapCenter.latitude, mapCenter.longitude, 30000)
			prevZoom = mapView.zoom
			prevLang = preferredLang
			GetNearbyPlacesImagesTask(
				app,
				prevMapRect, prevZoom,
				prevLang, loadNearbyPlacesListener).execute()
		} else {
			notifyListeners()
		}
	}

	private fun updateLastModifiedTime() {
		lastModifiedTime = System.currentTimeMillis()
	}

	fun getLastModifiedTime(): Long {
		return lastModifiedTime
	}

	fun showPointInContextMenu(mapActivity: MapActivity, point: NearbyPlacePoint) {
		val latitude: Double = point.latitude
		val longitude: Double = point.longitude
		app.settings.setMapLocationToShow(
			latitude,
			longitude,
			SearchCoreFactory.PREFERRED_NEARBY_POINT_ZOOM,
			point.getPointDescription(app),
			true,
			point)
		MapActivity.launchMapActivityMoveToTop(mapActivity)
	}

	fun getAmenity(latLon: LatLon, osmId: Long): Amenity? {
		var foundAmenity: Amenity? = null
		val radius = NEARBY_MIN_RADIUS
		val rect = MapUtils.calculateLatLonBbox(latLon.latitude, latLon.longitude, radius)
		app.resourceManager.searchAmenities(
			BinaryMapIndexReader.ACCEPT_ALL_POI_TYPE_FILTER,
			rect.top, rect.left, rect.bottom, rect.right,
			-1, true,
			object : ResultMatcher<Amenity?> {
				override fun publish(amenity: Amenity?): Boolean {
					val id = ObfConstants.getOsmObjectId(amenity)
					if (osmId == id) {
						foundAmenity = amenity
					}
					return false
				}

				override fun isCancelled(): Boolean {
					return foundAmenity != null
				}
			})
		return foundAmenity
	}
}