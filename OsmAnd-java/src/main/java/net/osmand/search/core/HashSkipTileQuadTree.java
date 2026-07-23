package net.osmand.search.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import gnu.trove.list.array.TIntArrayList;
import net.osmand.util.MapUtils;

public class HashSkipTileQuadTree<T> {

	public record QuadTreePair<T, R>(T o1, R o2) {}

	public static final int MAX_ZOOM = 16;
	public static final int MIN_ZOOM = 0;
	public static final int[] INDEXED_ZOOMS = new int[] { 5, 8, 11, 14, 16 };
	
	private final List<TileEntry<T>> tileEntries = new ArrayList<>();
	private final ZoomBucket[] zoomBuckets = new ZoomBucket[MAX_ZOOM + 1];
	
	
	public static class TileEntry<T> {
		public final long objId;
		public final T obj;
		public final int[] bbox31;
		public final int z;
		public final long tileId;
		public int skipNextTileId = 0;
		

		TileEntry(long objId, T obj, int[] bbox31, int z, long tileId) {
			this.objId = objId;
			this.obj = obj;
			this.bbox31 = bbox31;
			this.z = z;
			this.tileId = tileId;
		}
	}
	
	static class ZoomBucket {
		public int z;
		public int start;
		public int len;
		public int[] indexedZooms;
		
		public TIntArrayList[] skipIndexes;
		public TIntArrayList[] skipSubIndexes;
		// used only for build
		long[] lastTileIds;
		int[] lastTileLen;

		ZoomBucket(int z, int start, int[] indexedZooms) {
			this.z = z;
			this.start = start;
			this.indexedZooms = indexedZooms;
			this.skipIndexes = new TIntArrayList[indexedZooms.length];
			this.skipSubIndexes = new TIntArrayList[indexedZooms.length];
			for(int k = 0; k < indexedZooms.length; k++) {
				this.skipIndexes[k] = new TIntArrayList();
				this.skipSubIndexes[k] = new TIntArrayList();
			}
		}
	}


	public void addObject(T obj, int[] bbox31, long externalId) {
		long objId = externalId == -1 ? tileEntries.size() : externalId;
		int targetZoom = MAX_ZOOM;
		// Fit into 2x2 - maximum 4 tiles
		while (targetZoom > MIN_ZOOM) {
			int shift = 31 - targetZoom;
			int minX = bbox31[0] >> shift;
			int maxX = bbox31[2] >> shift;
			int minY = bbox31[1] >> shift;
			int maxY = bbox31[3] >> shift;
			if ((maxX - minX <= 1) && (maxY - minY <= 1)) {
				if ((minX >> 1) == (maxX >> 1) && (minY >> 1) == (maxY >> 1)) {
					targetZoom--;
				}
				break;
			}
			targetZoom--;
		}
		addTileEntry(obj, bbox31, objId, targetZoom);
	}
	

	public static long encodeTileId(int x, int y) {
		return MapUtils.interleaveBits(x, y);
	}

	private static boolean intersectsBBox(int[] a, int[] b) {
		return a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1];
	}


	private void addTileEntry(T obj, int[] bbox31, long objId, int targetZoom ) {
		int shift = 31 - targetZoom;
		int minX = bbox31[0] >> shift;
		int maxX = bbox31[2] >> shift;
		int minY = bbox31[1] >> shift;
		int maxY = bbox31[3] >> shift;
		for(int x = minX; x <= maxX; x++) {
			for(int y = minY; y <= maxY; y++) {
				tileEntries.add(new TileEntry<>(objId, obj, bbox31, targetZoom, encodeTileId(x, y)));
			}
		}
	}

	public void build() {
		tileEntries.clear();
		tileEntries.sort((e1, e2) -> {
			if (e1.z != e2.z) return Integer.compare(e1.z, e2.z);
			return Long.compare(e1.tileId, e2.tileId);
		});
		ZoomBucket zoomBucket = null;
		int lastZoom = -1;
		long lastTileId = 0;
		int lastTileIdFirstInd = -1;
		int index = 0;
		for (index = 0; index <= tileEntries.size(); index++) {
			boolean lastProc = tileEntries.size() == index;
			TileEntry<T> tileE = lastProc ? null : tileEntries.get(index);
			long tileId = lastProc ? 0 : tileE.tileId;
			int tileZoom = lastProc ? lastZoom : tileE.z; 
			if (lastZoom != tileZoom) {
				lastZoom = tileE.z;
				TIntArrayList indexedZooms = new TIntArrayList();
				for (int iz : INDEXED_ZOOMS) {
					if (iz >= tileE.z) {
						break;
					}
					indexedZooms.add(iz);
				}
				zoomBucket = new ZoomBucket(lastZoom, index, indexedZooms.toArray());
				zoomBuckets[tileE.z] = zoomBucket;
			}
			if ((lastTileId != tileId || lastZoom != tileE.z)) {
				if (lastTileIdFirstInd >= 0) {
					tileEntries.get(lastTileIdFirstInd).skipNextTileId = index - lastTileIdFirstInd;
				}
				lastTileId = tileId;
				lastTileIdFirstInd = index;
			}
			if (zoomBucket != null) {
				for (int kz = 0; kz < zoomBucket.indexedZooms.length; kz++) {
					int zoom = zoomBucket.indexedZooms[kz];
					long tileIdZoom = tileE.tileId >> ((zoomBucket.z - zoom) * 2);
					if (tileIdZoom == zoomBucket.lastTileIds[kz]) {
						zoomBucket.lastTileLen[kz]++;
					} else {
						zoomBucket.skipIndexes[kz].add(zoomBucket.lastTileLen[kz]);
					}
				}
				zoomBucket.len++;
			}
		}
	}
	
	
	private class TileIterator implements Iterator<TileEntry<T>> {
	    private final ZoomBucket bucket;
	    private final int zoom;

	    private int currentIndex;
	    private final int endIndex;

	    private TileEntry<T> nextEntry;

	    public TileIterator(ZoomBucket bucket, int zoom) {
	        this.bucket = bucket;
	        this.zoom = zoom;
	        this.currentIndex = bucket.start;
	        this.endIndex = bucket.start + bucket.len;

	        advance();
	    }

	    public TileEntry<T> getCurrentTile() {
	        if (currentIndex < endIndex) {
	            return tileEntries.get(currentIndex);
	        }
	        return null;
	    }

	    public void moveToNextTile() {
	        if (currentIndex >= endIndex) return;

	        TileEntry<T> entry = tileEntries.get(currentIndex);
	        int jump = (entry.skipNextTileId > 0) ? entry.skipNextTileId : 1;
	        currentIndex += jump;
	    }

	    public void moveToNextBlock(int indexedZoom) {
	        if (currentIndex >= endIndex) return;

	        TileEntry<T> entry = tileEntries.get(currentIndex);
	        long parentTileId = entry.tileId >> ((zoom - indexedZoom) * 2);

//	        int jumpSize = bucket.getCurrentTileBlock(indexedZoom, currentIndex, parentTileId);
//	        currentIndex += Math.max(1, jumpSize);
	    }

	    public int[] getIndexedZooms() {
	        return bucket.indexedZooms;
	    }
	    
	    @Override
	    public boolean hasNext() {
	        return nextEntry != null;
	    }

	    @Override
	    public TileEntry<T> next() {
	        if (nextEntry == null) {
	            throw new NoSuchElementException();
	        }
	        TileEntry<T> current = nextEntry;
	        advance();
	        return current;
	    }

	    private void advance() {
	        nextEntry = null;
			int[] bbox31 = null;
	        while (currentIndex < endIndex) {
	            TileEntry<T> entry = getCurrentTile();
	            if (entry == null) break;

	            int[] indexedZooms = getIndexedZooms();
	            boolean skippedByBlock = false;

	            if (indexedZooms != null) {
	                for (int kz = 0; kz < indexedZooms.length; kz++) {
	                    int skipZ = indexedZooms[kz];
	                    long parentTileId = entry.tileId >> ((zoom - skipZ) * 2);

	                    if (!intersectsTileIdBBox31(parentTileId, skipZ, bbox31)) {
	                        moveToNextBlock(skipZ);
	                        skippedByBlock = true;
	                        break;
	                    }
	                }
	            }

	            if (skippedByBlock) {
	                continue;
	            }


	            currentIndex++;
	        }
	    }
	}
	
//	public List<TileEntry<T>> get(int z, int[] bbox31) {
//		List<TileEntry<T>> result = new ArrayList<>();
//		ZoomBucket bucket = zoomBuckets[z];
//		if (bucket == null) return result;
//		
//		int i = bucket.start;
//	    int end = bucket.start + bucket.len;
//	    while (i < end) {
//	        TileEntry<T> entry = tileEntries.get(i);
//	        boolean skippedByParent = false;
//	            for (int kz = 0; kz < bucket.indexedZooms.length; kz++) {
//	                int skipZ = bucket.indexedZooms[kz];
//	                long parentTileId = entry.tileId >> ((z - skipZ) * 2);
//	                if (!intersectsTileIdBBox31(parentTileId, skipZ, bbox31)) {
//	                    TIntArrayList skipList = bucket.skipIndexes[kz];
//	                    int jumpSize = getSkipJumpSize(bucket, kz, i, parentTileId);
//	                    i += Math.max(1, jumpSize);
//	                    skippedByParent = true;
//	                    break;
//	                }
//	            }
//
//	        if (skippedByParent) {
//	            continue;
//	        }
//
//	        if (!intersectsTileIdBBox31(entry.tileId, z, bbox31)) {
//	            i += (entry.skipNextTileId > 0) ? entry.skipNextTileId : 1;
//	            continue;
//	        }
//	        if (intersectsBBox(entry.bbox31, bbox31)) {
//	            result.add(entry);
//	        }
//
//	        i++; 
//	    }
//		return result;
//	}
	
	// TODO speedup
	private static boolean intersectsTileIdBBox31(long tileId, int tileZoom, int[] bbox31) {
	    int shift = 31 - tileZoom;
	    int tileX = (int) MapUtils.deinterleaveX(tileId);
	    int tileY = (int) MapUtils.deinterleaveY(tileId);
	    int tileMinX = tileX << shift;
	    int tileMaxX = ((tileX + 1) << shift) - 1;
	    int tileMinY = tileY << shift;
	    int tileMaxY = ((tileY + 1) << shift) - 1;
	    return tileMinX <= bbox31[2] && tileMaxX >= bbox31[0] &&
	           tileMinY <= bbox31[3] && tileMaxY >= bbox31[1];
	}
//	
//	public <R> List<QuadTreePair<T, R>> intersect(int z1, int z2, HashSkipTileQuadTree<R> otherTree) {
//		List<QuadTreePair<T, R>> result = new ArrayList<>();
//
//		ZoomBucket b1 = this.zoomBuckets[z1];
//		ZoomBucket b2 = otherTree.zoomBuckets[z2];
//
//		if (b1 == null || b2 == null) {
//			return result;
//		}
//
//		// 1. Одинаковый зум: двух-указательный скан O(K1 + K2)
//		if (z1 == z2) {
//			int idx1 = 0, idx2 = 0;
//			while (idx1 < b1.tileIds.length && idx2 < b2.tileIds.length) {
//				long t1 = b1.tileIds[idx1];
//				long t2 = b2.tileIds[idx2];
//
//				if (t1 == t2) {
//					int start1 = b1.refs[idx1];
//					int end1 = (idx1 + 1 < b1.tileIds.length) ? b1.refs[idx1 + 1] : (b1.start + b1.len);
//
//					int start2 = b2.refs[idx2];
//					int end2 = (idx2 + 1 < b2.tileIds.length) ? b2.refs[idx2 + 1] : (b2.start + b2.len);
//
//					checkBBoxIntersections(start1, end1, start2, end2, otherTree, result);
//					idx1++;
//					idx2++;
//				} else if (t1 < t2) {
//					idx1++;
//				} else {
//					idx2++;
//				}
//			}
//			return result;
//		}
//
//		// 2. Разные зумы: Использование SkipIndex
//		int parentZoom = Math.min(z1, z2);
//		int childZoom = Math.max(z1, z2);
//		int targetIndexedZoom = getNearestIndexedZoom(childZoom);
//
//		boolean isCurrentParent = (z1 < z2);
//		HashSkipTileQuadTree<?> parentTree = isCurrentParent ? this : otherTree;
//		HashSkipTileQuadTree<?> childTree = isCurrentParent ? otherTree : this;
//
//		ZoomBucket parentBucket = parentTree.zoomBuckets[parentZoom];
//		SkipIndex childSkipIndex = childTree.skipIndices[targetIndexedZoom];
//
//		if (parentBucket == null || childSkipIndex == null) {
//			return result;
//		}
//
//		int shift = (targetIndexedZoom - parentZoom) * 2;
//
//		for (int i = 0; i < parentBucket.tileIds.length; i++) {
//			long pTileId = parentBucket.tileIds[i];
//			long minChildTileId = pTileId << shift;
//			long maxChildTileId = ((pTileId + 1) << shift) - 1;
//
//			int matchIdx = Arrays.binarySearch(childSkipIndex.tileIds, minChildTileId);
//			if (matchIdx < 0) matchIdx = -matchIdx - 1;
//
//			int start1 = parentBucket.refs[i];
//			int end1 = (i + 1 < parentBucket.tileIds.length) ? parentBucket.refs[i + 1] : (parentBucket.start + parentBucket.len);
//
//			for (int j = matchIdx; j < childSkipIndex.tileIds.length && childSkipIndex.tileIds[j] <= maxChildTileId; j++) {
//				int start2 = childSkipIndex.startRefs[j];
//				int end2 = childSkipIndex.endRefs[j];
//
//				if (isCurrentParent) {
//					checkBBoxIntersections(start1, end1, start2, end2, (HashSkipTileQuadTree<R>) childTree, result);
//				} else {
//					// Исправлен порядок объектов при обратном вызове
//					((HashSkipTileQuadTree) childTree).checkBBoxIntersections(
//							start2, end2, start1, end1, parentTree, new ReversePairAdapter<>(result)
//					);
//				}
//			}
//		}
//
//		return result;
//	}
//
//
//	private <R> void checkBBoxIntersections(
//			int start1, int end1, int start2, int end2,
//			HashSkipTileQuadTree<R> otherTree, List<QuadTreePair<T, R>> result) {
//
//		for (int i = start1; i < end1; i++) {
//			TileEntry<T> e1 = this.tileEntries.get(i);
//
//			for (int j = start2; j < end2; j++) {
//				TileEntry<R> e2 = otherTree.tileEntries.get(j);
//
//				if (intersectsBBox(e1.bbox31, e2.bbox31)) {
//					result.add(new QuadTreePair<>(e1.obj, e2.obj));
//				}
//			}
//		}
//	}
//
//	private int getNearestIndexedZoom(int targetZoom) {
//		for (int iz : INDEXED_ZOOMS) {
//			if (iz >= targetZoom) return iz;
//		}
//		return MAX_ZOOM;
//	}
//
//
//	private static class ReversePairAdapter<T, R> extends ArrayList<QuadTreePair<R, T>> {
//		private final List<QuadTreePair<T, R>> target;
//
//		ReversePairAdapter(List<QuadTreePair<T, R>> target) {
//			this.target = target;
//		}
//
//		@Override
//		public boolean add(QuadTreePair<R, T> pair) {
//			return target.add(new QuadTreePair<>(pair.o2(), pair.o1()));
//		}
//	}
}