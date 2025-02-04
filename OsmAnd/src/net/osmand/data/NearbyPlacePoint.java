package net.osmand.data;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.util.Algorithms;
import net.osmand.wiki.WikiCoreHelper;
import net.osmand.wiki.WikiCoreHelper.OsmandApiFeatureData;
import net.osmand.wiki.WikiImage;

import java.io.Serializable;


public class NearbyPlacePoint implements Serializable, LocationPoint {

	private static final long serialVersionUID = 829654300829771466L;

	public static final BackgroundType DEFAULT_BACKGROUND_TYPE = BackgroundType.CIRCLE;
	private final long id;
	private final String photoTitle;
	private final String wikiTitle;
	private final String poitype;
	private final String poisubtype;
	private final String wikiDesc;
	private final String iconUrl;
	private final double latitude;
	private final double longitude;
	@Nullable
	private Bitmap imageBitmap;

	public NearbyPlacePoint(OsmandApiFeatureData featureData) {
		this.id = featureData.properties.osmid;
		WikiImage wikiIMage = WikiCoreHelper.getImageData(featureData.properties.photoTitle);
		this.iconUrl = wikiIMage == null ? "" : wikiIMage.getImageIconUrl();
		this.latitude = featureData.geometry.coordinates[1];
		this.longitude = featureData.geometry.coordinates[0];
		this.photoTitle = featureData.properties.photoTitle;
		this.poisubtype = featureData.properties.poisubtype;
		this.poitype = featureData.properties.poitype;
		this.wikiTitle = featureData.properties.wikiTitle;
		this.wikiDesc = featureData.properties.wikiDesc;
	}

	public long getId() {
		return id;
	}

	@Nullable
	public Bitmap getImageBitmap() {
		return imageBitmap;
	}

	public void setImageBitmap(@Nullable Bitmap imageBitmap) {
		this.imageBitmap = imageBitmap;
	}

	public String getIconUrl() {
		return iconUrl;
	}

	public String getWikiTitle() {
		return wikiTitle;
	}

	@Override
	public boolean isVisible() {
		return true;
	}

	public String getKey() {
		return photoTitle;
	}

	@Override
	public PointDescription getPointDescription(@NonNull Context ctx) {
		return new PointDescription(PointDescription.POINT_TYPE_NEARBY_PLACE, wikiDesc);
	}

	public double getLatitude() {
		return latitude;
	}

	public double getLongitude() {
		return longitude;
	}

	@Override
	public int getColor() {
		return 0;
	}

	public String getDisplayName(@NonNull Context ctx) {
		return wikiTitle;
	}

	public String getName() {
		return wikiTitle;
	}

	public String getDescription() {
		return wikiDesc;
	}

	@NonNull
	@Override
	public String toString() {
		return "NearbyPlace " + getName();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null) {
			return false;
		}
		if (getClass() != o.getClass()) {
			return false;
		}

		NearbyPlacePoint point = (NearbyPlacePoint) o;
		if (!Algorithms.stringsEqual(photoTitle, point.photoTitle)) {
			return false;
		}
		if (!Algorithms.stringsEqual(wikiTitle, point.wikiTitle)) {
			return false;
		}
		if (!Algorithms.stringsEqual(poitype, point.poitype)) {
			return false;
		}
		if (!Algorithms.stringsEqual(poisubtype, point.poisubtype)) {
			return false;
		}

		if (!Algorithms.stringsEqual(wikiDesc, point.wikiDesc)) {
			return false;
		}

		return Double.compare(this.latitude, point.latitude) == 0
				&& Double.compare(this.longitude, point.longitude) == 0;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (int) Math.floor(latitude * 10000);
		result = prime * result + (int) Math.floor(longitude * 10000);
		result = prime * result + ((photoTitle == null) ? 0 : photoTitle.hashCode());
		result = prime * result + ((wikiTitle == null) ? 0 : wikiTitle.hashCode());
		result = prime * result + ((poitype == null) ? 0 : poitype.hashCode());
		result = prime * result + ((poisubtype == null) ? 0 : poisubtype.hashCode());
		result = prime * result + ((wikiDesc == null) ? 0 : wikiDesc.hashCode());
		return result;
	}
}
