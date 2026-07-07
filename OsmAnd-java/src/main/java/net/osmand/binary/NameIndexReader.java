package net.osmand.binary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import gnu.trove.iterator.TLongIterator;
import gnu.trove.list.array.TLongArrayList;
import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.set.hash.TLongHashSet;
import net.osmand.Collator;
import net.osmand.CollatorStringMatcher;
import net.osmand.CollatorStringMatcher.StringMatcherMode;
import net.osmand.OsmAndCollator;
import net.osmand.binary.BinaryMapAddressReaderAdapter.AddressRegion;
import net.osmand.binary.BinaryMapAddressReaderAdapter.CityBlocks;
import net.osmand.binary.BinaryMapPoiReaderAdapter.PoiRegion;
import net.osmand.binary.OsmandOdb.AddressNameIndexDataAtom;
import net.osmand.binary.OsmandOdb.CommonIndexedStats;
import net.osmand.binary.OsmandOdb.OsmAndAddressNameIndexData.AddressNameIndexData;
import net.osmand.binary.OsmandOdb.OsmAndPoiNameIndex.OsmAndPoiNameIndexData;
import net.osmand.binary.OsmandOdb.OsmAndPoiNameIndexDataAtom;
import net.osmand.data.City;
import net.osmand.data.QuadRect;
import net.osmand.util.SearchAlgorithms;

public class NameIndexReader {

	public static final String CITY_AS_STREET_COMMON = "cityasstreetcommon";
	
	// read params
	public final PoiRegion poiRegion;
	public final AddressRegion addressRegion;
	
	// cache for queries 
	private Map<String, TLongHashSet> matchedKeys = new HashMap<String, TLongHashSet>();
	// cache for prefixes
	private Map<Long, PrefixNameValue> indexByRef = new HashMap<>();
	private long tablePointer;
	
	// common words
	private CommonIndexedStats commonStats;
	private List<String> commonsList = new ArrayList<String>();
	private Map<String, ValueFreq> commonStatsValues = null;
	
	// stats
	private NameIndexReaderBytes bytesStat = new NameIndexReaderBytes();
	private SuffixesStat suffixesStat;
	private StreetsIndexStat streetsStat;
	private BoundariesIndexStat bndsStat;
	
	NameIndexReaderQuery query = null; // read all
	
	
	public static class NameIndexReaderBytes {
		public long readTableBytes;
		public long skipTableBytes;
		public long readAtomBytes;
		public long skipAtomBytes;
	}
	
	public static class NameIndexReaderMatcher {

		protected String queryAligned;
		protected String queryIncomplete;
		protected Collator collator;
		
		public NameIndexReaderMatcher(String query) {
			queryAligned = SearchAlgorithms.alignChars(query);
			collator = OsmAndCollator.primaryCollator();
			if (query.endsWith(CollatorStringMatcher.INCOMPLETE_DOT + "")) {
				queryIncomplete = query.substring(0, query.length() - 1);
			}
		}
		
		public boolean matchKey(String key) {
			String alignedKey = SearchAlgorithms.alignChars(key);
			return matchAlignedKey(alignedKey);
		}

		protected boolean matchAlignedKey(String alignedKey) {
			// 1. simple match
			boolean match = CollatorStringMatcher.cmatches(collator, queryAligned, alignedKey, StringMatcherMode.CHECK_ONLY_STARTS_WITH);
			// 2. match 2-xx (key) -> 2 (query) - another solution number, NC-42 (key) -> NC (query) or 'NC 42' 2 tokens  
			if (!match && alignedKey.indexOf('-') != -1) {
				// check equals for any substring ('-' is space for collator) - mostly we interested in first part before '-' for equals
				match = CollatorStringMatcher.cmatches(collator, alignedKey, queryAligned, StringMatcherMode.CHECK_EQUALS_FROM_SPACE);
				// case data - '2-x...' query '2xyz', we check that user writes without space
				if (!match) {
					String alignedSingleWord = alignedKey.replace("-", "");
					match = CollatorStringMatcher.cmatches(collator, queryAligned, alignedSingleWord, StringMatcherMode.CHECK_ONLY_STARTS_WITH);
				}
			}
//			match = query.startsWith(key);
			// 3. incomplete query match
			if (!match && queryIncomplete != null) {
				match = CollatorStringMatcher.cmatches(collator, queryIncomplete, alignedKey, StringMatcherMode.CHECK_ONLY_STARTS_WITH) ||
						CollatorStringMatcher.cmatches(collator, alignedKey, queryIncomplete, StringMatcherMode.CHECK_ONLY_STARTS_WITH);
//				match = key.startsWith(pr) || pr.startsWith(key);
			}
			return match;
		}
	}
	
	// Active query 
	static class NameIndexReaderQuery {
		String query;
		TLongHashSet matchedKeys = new TLongHashSet();
		NameIndexReaderMatcher matcher;
		
		public NameIndexReaderQuery(String query, NameIndexReaderMatcher matcher) {
			this.query = query;
			this.matcher = matcher;
		}
		
		public void addMatchedKey(long shift) {
			matchedKeys.add(shift);
		}

		public boolean matchKey(String key) {
			return matcher.matchKey(key);
		}
	}
	

	public NameIndexReader(AddressRegion p) {
		this.poiRegion = null;
		this.addressRegion = p;
	}
	
	public NameIndexReader(PoiRegion p) {
		this.poiRegion = p;
		this.addressRegion = null;
	}

	public boolean readAll() {
		return query == null;
	}
	
	public void setTablePointer(long totalBytesRead) {
		this.tablePointer = totalBytesRead;
	}

	
	public static class BoundariesIndexStat {
		
		Map<String, ValueFreq> bnds = new HashMap<>();
		
		TLongObjectHashMap<QuadRect> rects = new TLongObjectHashMap<QuadRect>();
		
		public Map<String, ValueFreq> getBoundaries() {
			return bnds;
		}
		
		public void registerBoundaries(List<City> cities) {
			for (City c : cities) {
				long oid = ObfConstants.getOsmObjectId(c);
				int[] bbox31 = c.getBbox31();
				if (bbox31 == null) {
					continue;
				}
				rects.put(oid, new QuadRect(bbox31[0], bbox31[1], bbox31[2], bbox31[3]));
				addSubValue(ValueFreq.get(bnds, c.getName(), 0), oid);
				for (String s : c.getOtherNames()) {
					addSubValue(ValueFreq.get(bnds, s, 0), oid);
				}
			}
		}

		private void addSubValue(ValueFreq mainWord, long oid) {
			if (mainWord.subValues == null) {
				mainWord.subValues = new ArrayList<>();
			}
			for (ValueFreq v : mainWord.subValues) {
				if (v.extra == oid) {
					v.freq++;
					return;
				}
			}
			ValueFreq v = new ValueFreq(mainWord.value, 1);
			v.extra = oid;
			mainWord.subValues.add(v);
			mainWord.freq++;
		}
		
		public int calculateNumberOfDistinctBBox(List<ValueFreq> vls) {
			if(vls.size() < 2) {
				return vls.size();
			}
			List<List<QuadRect>> groups = new ArrayList<List<QuadRect>>();
			int KM15_IN31Z = 1_000_000;
			for (int i = 0; i < vls.size(); i++) {
				QuadRect q = rects.get(vls.get(i).extra);
				q.inset(-Math.max(KM15_IN31Z, q.width() / 6), -Math.max(KM15_IN31Z, q.height() / 6));
				List<QuadRect> group = null;
				main: for (List<QuadRect> gr : groups) {
					for (QuadRect r : gr) {
						if (QuadRect.trivialOverlap(r, q)) {
							group = gr;
							break main;
						}
					}
				}
				if (group == null) {
					group = new ArrayList<QuadRect>();
					groups.add(group);
				}
				group.add(q);
			}
			return groups.size();
		}

		public void merge(BoundariesIndexStat bndsStat) {
			rects.putAll(bndsStat.rects);
			for (ValueFreq from : bndsStat.bnds.values()) {
				ValueFreq to = bnds.get(from.value);
				if (to == null) {
					bnds.put(from.value, from);
				} else {
					for (ValueFreq fromSub : from.subValues) {
						ValueFreq toSub = null;
						for (ValueFreq t : from.subValues) {
							if (t.extra == fromSub.extra) {
								toSub = t;
								toSub.freq += fromSub.freq;
								break;
							}
						}
						if (toSub == null) {
							to.subValues.add(fromSub);
							to.freq++;
						}
					}
				}
			}
		}

	}
	
	public static class StreetsIndexStat {

		Map<String, ValueFreq> values = new HashMap<>();
		
		public void merge(StreetsIndexStat streetsStat) {
			ValueFreq.mergeArray(values, streetsStat.values);
		}

		public void processAtom(ValueFreq v, ValueFreq sPref, AddressNameIndexDataAtom a) {
			if (a.getType() == CityBlocks.STREET_TYPE.index) {
				sPref.extra++;
				v.extra++;
				sPref.freq++;
				v.freq++;
			} else if (a.getEnclosingObjects() > 0) {
				sPref.enclosing += a.getEnclosingObjects();
				v.enclosing += a.getEnclosingObjects();
				sPref.freq += a.getEnclosingObjects();
				v.freq += a.getEnclosingObjects();
				sPref.maxSingleAtomEnc = Math.max(sPref.maxSingleAtomEnc, a.getEnclosingObjects());
				v.maxSingleAtomEnc = Math.max(v.maxSingleAtomEnc, a.getEnclosingObjects());
			}
		}
		
		public Map<String, ValueFreq> getValues() {
			return values;
		}
		
		
	}
	
	public static class SuffixesStat {
		List<ValueFreq> longestSuffixes = new ArrayList<>();
		String longestSuffixesKey;
		int suffixesLenSum;
		int prefixesCount;
		int atomCount;
		
		public void merge(SuffixesStat suffixesStat) {
			if (suffixesStat == null) {
				return;
			}
			if (longestSuffixes.size() < suffixesStat.longestSuffixes.size()) {
				this.longestSuffixes = suffixesStat.longestSuffixes;
				this.longestSuffixesKey = suffixesStat.longestSuffixesKey;
				
			}
			this.atomCount += suffixesStat.atomCount;
			this.prefixesCount += suffixesStat.prefixesCount;
			this.suffixesLenSum += suffixesStat.suffixesLenSum;
		}
		
		public String toString(String nl) {
			int sz = longestSuffixes.size();
			String longestStr = String.format("Name Suffixes - Longest suffixes '%s' (%d): %s...", longestSuffixesKey,
					longestSuffixes.size(), longestSuffixes.subList(0, Math.min(30, sz)));
			return longestStr;
		}

		@Override
		public String toString() {
			return toString("\n");
		}
		
	}
	
	public static class ValueFreq implements Comparable<ValueFreq> {
		public String value;
		public int freq;
		public long extra;
		public int enclosing;
		public int maxSingleAtomEnc;
		public int maxSingleSubValueEnc;
		public List<ValueFreq> subValues = null;
		
		public static boolean SORT_BY_NAME = false;
		public static boolean SORT_BY_TOP_FREQ = true;
		
		public ValueFreq(String name, int frequency) {
			this.value = name;
			this.freq = frequency;
		}
		
		
		public static void sort(List<ValueFreq> lst) {
			Collections.sort(lst);
		}
		
		public static void sortMain(List<ValueFreq> lst) {
			Collections.sort(lst, new Comparator<ValueFreq>() {

				@Override
				public int compare(ValueFreq o1, ValueFreq o2) {
					return -Integer.compare(o1.freq, o2.freq);
				}
				
			});
		}
		
		public ValueFreq copy() {
			ValueFreq vf = new ValueFreq(value, freq);
			vf.extra = extra;
			vf.enclosing = enclosing;
			vf.maxSingleAtomEnc = maxSingleAtomEnc;
			vf.maxSingleSubValueEnc = maxSingleSubValueEnc;
			if (subValues != null) {
				vf.subValues = new ArrayList<>();
				for (ValueFreq s : subValues) {
					vf.subValues.add(s.copy());
				}
			}
			return vf;
		}

		public List<ValueFreq> getSubvalues(double percent, int min) {
			if (subValues == null || subValues.size() == 0) {
				return Collections.emptyList();
			}
			int limit = Math.min(min, subValues.size());
			for (; limit < subValues.size(); limit++) {
				if (subValues.get(limit).freq < percent * freq) {
					break;
				}
			}
			return subValues.subList(0, limit);
		}
		
		public static ValueFreq get(Map<String, ValueFreq> values, String key, int def) {
			ValueFreq vf = values.get(key);
			if (vf == null) {
				vf = new ValueFreq(key, def);
				values.put(key, vf);
			}
			return vf;
		}

		public static Map<String, ValueFreq> mergeArray(Map<String, ValueFreq> res, List<ValueFreq> m) {
			for (ValueFreq s : m) {
				if (res.containsKey(s.value)) {
					res.get(s.value).merge(s);
				} else {
					res.put(s.value, s.copy());
				}
			}
			return res;
		}
		
		public static Map<String, ValueFreq> mergeFlatten(Map<String, ValueFreq> r, Collection<ValueFreq> ms) {
			for (ValueFreq s : ms) {
				if (s.subValues != null) {
					mergeFlatten(r, s.subValues);
				} else if (!r.containsKey(s.value)) {
					r.put(s.value, s.copy());
				} else {
					r.get(s.value).merge(s);
				}
			}
			return r;
		}

		public static Map<String, ValueFreq> mergeArray(Map<String, ValueFreq> res, Map<String, ValueFreq> ms) {
			for (ValueFreq s : ms.values()) {
				ValueFreq vf = res.get(s.value);
				if (vf != null) {
					vf.merge(s);
				} else {
					res.put(s.value, s.copy());
				}
			}
			return res;
		}
		
		
		public void merge(ValueFreq s) {
			this.freq += s.freq;
			this.enclosing += s.enclosing;
			this.maxSingleAtomEnc = Math.max(maxSingleAtomEnc, s.maxSingleAtomEnc);
			if (subValues == null && s.subValues != null) {
				s.subValues = new ArrayList<>();
			}
			this.extra += s.extra;
			if (subValues != null) {
				subValues = new ArrayList<>(
						mergeArray(mergeArray(new TreeMap<String, ValueFreq>(), subValues), s.subValues).values());
				for (ValueFreq v : subValues) {
					this.maxSingleSubValueEnc = Math.max(this.maxSingleSubValueEnc, v.enclosing);
				}
			}
		}

		@Override
		public String toString() {
			if (enclosing > 0) {
				String enc = String.format(", enc %,d/%,d", enclosing, maxSingleAtomEnc);
				if (subValues != null) {
					enc = String.format(", enc %,d/%,d/%,d", enclosing, maxSingleSubValueEnc, maxSingleAtomEnc);
				}
				String extraS = "";
				if (extra > 0) {
					extraS = String.format(", ex %,d", extra);
				}
				return String.format("%s (%,d%s%s)", value, freq, extraS, enc);
			}
			return String.format("%s (%,d)", value, freq);
		}
		
		public int getTopFreq() {
			if(subValues != null && subValues.size() > 0) {
				Collections.sort(subValues);
				return subValues.get(0).freq;
			}
			return freq;
		}

		@Override
		public int compareTo(ValueFreq o) {
			if (!SORT_BY_NAME) {
				if (SORT_BY_TOP_FREQ) {
					int c = -Integer.compare(getTopFreq(), o.getTopFreq());
					if (c != 0) {
						return c;
					}
				}
				int c = -Integer.compare(freq, o.freq);
				if (c != 0) {
					return c;
				}
			}
			return value.compareTo(o.value);
		}
	}
	
	public class PrefixNameValue implements Comparable<PrefixNameValue> {
		public String key;
		public OsmAndPoiNameIndexData poi = null;
		public AddressNameIndexData addr = null;
		public long shift;
		
		
		@Override
		public String toString() {
			if (poi != null) {
				List<ValueFreq> suffixes = collectPOIFrequencies();
				return String.format("%s (%d, %s)", key, poi.getAtomsCount(), suffixes);
			} else if (addr != null) {
				List<ValueFreq> suffixes =  collectAddrFrequencies( key, null, null, -1);
				return String.format("%s (%d, %s)", key, addr.getAtomCount(), suffixes);
			} else {
				return key + " <NOT SET>";
			}
		}

		private List<ValueFreq> collectAddrFrequencies(String prefix, 
				SuffixesStat suffStats, StreetsIndexStat streetsStat, int f) {
			List<ValueFreq> suffixes = new ArrayList<>();
			Map<Integer, ValueFreq> intSuffixes = new HashMap<>();
			ValueFreq streetsPrefix = null;
			if (streetsStat != null) {
				streetsPrefix = new ValueFreq(prefix, 0);
				streetsPrefix.subValues = new ArrayList<>();
				streetsStat.values.put(prefix, streetsPrefix);
			}
			
			String curSuffix = "";
			if (suffStats != null) {
				suffStats.prefixesCount++;
				suffStats.suffixesLenSum += addr.getSuffixesDictionaryList().size();
			}
			for (String s : addr.getSuffixesDictionaryList()) {
				curSuffix = SearchAlgorithms.nameIndexDecodeDictionarySuffix(curSuffix, s);
				// not exactly correct as could be different values combinations
				String name = curSuffix.startsWith(" ") ? curSuffix :  key + curSuffix;
				suffixes.add(new ValueFreq(name, 0));
				if (streetsStat != null) {
					streetsPrefix.subValues.add(new ValueFreq(name, 0));
				}
			}
			for (Integer i : addr.getSuffixesCommonDictionaryList()) {
				String value = commonsList.get(i);
				suffixes.add(new ValueFreq(" " + value, 0));
				if (streetsStat != null) {
					streetsPrefix.subValues.add(new ValueFreq(" " + value, 0));
				}
			}
			if (suffStats != null && suffStats.longestSuffixes.size() < suffixes.size()) {
				suffStats.longestSuffixes = suffixes;
				suffStats.longestSuffixesKey = key;
			}
			for (AddressNameIndexDataAtom a : addr.getAtomList()) {
				if (a.getType() != f && f >= 0) {
					continue;
				}
				for (int i = 0; i < a.getSuffixesBitsetIndexCount(); i++) {
					int suffBit = a.getSuffixesBitsetIndex(i);
					if(suffBit == 0) {
						 // delimiter skip 
					} else if (suffBit % 2 == 0 && suffBit != 0) {
						int ind = suffBit / 2 - 1;
						ValueFreq s = suffixes.get(ind);
						s.freq++;
						s.enclosing += a.getEnclosingObjects();
						s.maxSingleAtomEnc = Math.max(s.maxSingleAtomEnc, a.getEnclosingObjects());
						if (streetsStat != null) {
							ValueFreq sPref = streetsPrefix.subValues.get(ind);
							streetsStat.processAtom(streetsPrefix, sPref, a);
						}
						// test bbox
//						ByteString bbox = a.getBbox();
//						if (bbox != null && a.hasBbox() && a.getXy16Count() >= 1) {
//							int xy16 = a.getXy16(0);
//							int x16 = (xy16 >>> 16);
//							int y16 = (xy16 & ((1 << 16) - 1));
//							int[] vls = SearchAlgorithms.decodeBboxForNameAtomsBytes(bbox, x16, y16);
//							System.out.println(String.format("%s %.5f %.5f %.5f %.5f", s.value,
//									MapUtils.get31LatitudeY(vls[1]), MapUtils.get31LongitudeX(vls[0]),
//									MapUtils.get31LatitudeY(vls[3]), MapUtils.get31LongitudeX(vls[2])));
//						}
					} else if (suffBit % 2 == 1) {
						ValueFreq vf = intSuffixes.get(suffBit);
						if (vf == null) {
							vf = new ValueFreq((suffBit % 4 == 1 ? " " : "") + (suffBit >> 2), 0);
							intSuffixes.put(suffBit, vf);
							suffixes.add(vf);
						}
						vf.freq++;
						vf.enclosing += a.getEnclosingObjects();
						vf.maxSingleAtomEnc = Math.max(vf.maxSingleAtomEnc, a.getEnclosingObjects());
					}
				}
				if (suffStats != null && f == -1) {
					suffStats.atomCount++;
				}
			}
			
			return suffixes;
		}
		
		private List<ValueFreq> collectPOIFrequencies() {
			List<ValueFreq> suffixes = new ArrayList<>();
			String curSuffix = "";
			if (suffixesStat != null) {
				suffixesStat.prefixesCount++;
				suffixesStat.suffixesLenSum += poi.getSuffixesDictionaryList().size();
			}
			Map<Integer, ValueFreq> intSuffixes = new HashMap<>();
			for (String s : poi.getSuffixesDictionaryList()) {
				curSuffix = SearchAlgorithms.nameIndexDecodeDictionarySuffix(curSuffix, s);
				// not exactly correct as could be different values combinations
				ValueFreq vf = new ValueFreq(curSuffix.startsWith(" ") ? curSuffix :  key + curSuffix, 0);
				suffixes.add(vf);
			}
			for (Integer i : poi.getSuffixesCommonDictionaryList()) {
				String value = commonsList.get(i);
				suffixes.add(new ValueFreq(" " + value, 0));
			}
			if (suffixesStat != null && suffixesStat.longestSuffixes.size() < suffixes.size()) {
				suffixesStat.longestSuffixes = suffixes;
				suffixesStat.longestSuffixesKey = key;
			}
			
			for (OsmAndPoiNameIndexDataAtom a : poi.getAtomsList()) {
				for (int i = 0; i < a.getSuffixesBitsetIndexCount(); i++) {
					int suffBit = a.getSuffixesBitsetIndex(i);
					if(suffBit == 0) {
						 // delimiter skip 
					} else if (suffBit % 2 == 0) {
						int ind = suffBit / 2 - 1;
						ValueFreq s = suffixes.get(ind);
						s.freq++;
					} else if (suffBit % 2 == 1) {
						ValueFreq vf = intSuffixes.get(suffBit);
						if (vf == null) {
							vf = new ValueFreq((suffBit % 4 == 1 ? " " : "") + (suffBit >> 2), 0);
							intSuffixes.put(suffBit, vf);
							suffixes.add(vf);
						}
						vf.freq++;
					}
				}
				if (suffixesStat != null) {
					suffixesStat.atomCount++;
				}
			}
			Collections.sort(suffixes);
			return suffixes;
		}

		@Override
		public int compareTo(PrefixNameValue o) {
			int c = -Integer.compare(poi.getAtomsCount(), o.poi.getAtomsCount());
			if (c == 0) {
				c = key.compareTo(o.key);
			}
			return c;
		}
	}
	
	
	public StreetsIndexStat getStreetsStat() {
		return streetsStat;
	}
	
	public void setStreetsStat(StreetsIndexStat streetsStat) {
		this.streetsStat = streetsStat;
	}

	public SuffixesStat getSuffixesStat() {
		return suffixesStat;
	}
	
	public void setSuffixesStat(SuffixesStat suffixesStat) {
		this.suffixesStat = suffixesStat;
	}


	public BoundariesIndexStat getBoundariesStat() {
		return bndsStat;
	}
	
	public void setBoundariesStat(BoundariesIndexStat bndsStat) {
		this.bndsStat = bndsStat;
	}
	
	
	public Map<String, ValueFreq> getCommonWordsStats() {
		if (commonStatsValues != null) {
			return commonStatsValues;
		}
		if (commonStats == null) {
			return null;
		}
		commonStatsValues = new HashMap<>();
		String name = null;
		int valueCount = commonStats.getValueCount();
		for (int i = 0; i < valueCount; i++) {
			name = SearchAlgorithms.nameIndexDecodeDictionarySuffix(name, commonStats.getValue(i));
			ValueFreq vf = new ValueFreq(name, commonStats.getMatched(i));
			vf.extra = commonStats.getMatched(i) - commonStats.getNonindexed(i);
			ValueFreq old = commonStatsValues.put(vf.value, vf);
			if (old != null) {
				throw new UnsupportedOperationException();
			}
		}
		return commonStatsValues;
	}

	public CommonIndexedStats getCommonStats() {
		return commonStats;
	}

	public void setCommonIndexed(CommonIndexedStats commonStats) {
		if (this.commonStats != null) {
			throw new IllegalStateException();
		}
		this.commonStats = commonStats;
		String name = null;
		for (String s : commonStats.getValueList()) {
			name = SearchAlgorithms.nameIndexDecodeDictionarySuffix(name, s);
			commonsList.add(name);
		}
	}
	
	public String getCommonIndexed(int ind) {
		return commonsList.get(ind);
	}
	
	public void resetMatchedKeys(String query) {
		if (query != null) {
			matchedKeys.put(query, new TLongHashSet());
		}
	}
	
	public boolean matchKey(String key) {
		if (query == null) {
			return true;
		}
		return query.matchKey(key);
	}
	
	public void putKey(String key, int val, String prefix) {
		long shift = tablePointer + val;
		if (query != null) {
			query.addMatchedKey(shift);
		}
		if (!indexByRef.containsKey(shift)) {
			PrefixNameValue nameValue = new PrefixNameValue();
			nameValue.key = key;
			indexByRef.put(shift, nameValue);
		}
	}
	
	
	public List<ValueFreq> getPOIPrefixes(String prefix) {
		List<ValueFreq> ls = new ArrayList<NameIndexReader.ValueFreq>();
		for (PrefixNameValue p : indexByRef.values()) {
			if (prefix != null && !(p.key.toLowerCase().startsWith(prefix) || prefix.toLowerCase().startsWith(p.key))) {
				continue;
			}
			ValueFreq vf = new ValueFreq(p.key, p.poi.getAtomsCount());
			vf.subValues = p.collectPOIFrequencies();
			ls.add(vf);
		}
		return ls;
	}
	

	public List<ValueFreq> getAddrPrefixes(int filter, String prefix) {
		List<ValueFreq> ls = new ArrayList<NameIndexReader.ValueFreq>();
		for (PrefixNameValue p : indexByRef.values()) {
			if (prefix != null && !(p.key.toLowerCase().startsWith(prefix) || prefix.toLowerCase().startsWith(p.key))) {
				continue;
			}
			List<ValueFreq> subvalues = filter == -1 ? 
					p.collectAddrFrequencies(p.key, suffixesStat, streetsStat, filter)
					: p.collectAddrFrequencies(p.key, null, null, filter);
//			int total = p.addr.getAtomCount();
			// always recalculate other fields too
//			if (filter >= 0 || !Algorithms.isEmpty(prefix)) {
			int enclosing = 0, maxSingleAtomEnc = 0, maxSingleSubValueEnc = 0;
			int total = 0;
			List<ValueFreq> sublist = new ArrayList<>();
			for (ValueFreq s : subvalues) {
				total += s.freq;
				enclosing += s.enclosing;
				maxSingleSubValueEnc = Math.max(s.enclosing, maxSingleSubValueEnc);
				maxSingleAtomEnc = Math.max(s.maxSingleAtomEnc, maxSingleAtomEnc);
				if (s.freq > 0) {
					sublist.add(s);
				}
			}
			if (sublist.size() == 0) {
				continue;
			}
			subvalues = sublist;
			ValueFreq vf = new ValueFreq(p.key, total);
			vf.subValues = subvalues;
			vf.enclosing = enclosing;
			vf.maxSingleAtomEnc = maxSingleAtomEnc;
			vf.maxSingleSubValueEnc = maxSingleSubValueEnc;
			ls.add(vf);
		}
		return ls;
	}

	
	@Override
	public String toString() {
		return indexByRef.toString();
	}

	public PrefixNameValue addData(OsmAndPoiNameIndexData from, long currentShift) {
		PrefixNameValue obj = indexByRef.get(currentShift);
		if (obj.poi != null) {
			throw new IllegalStateException(obj.toString());
		}
		obj.shift = currentShift;
		obj.poi = from;
		return obj;
	}
	
	
	public List<PrefixNameValue> getAtomsToLoad(TLongArrayList loffsets) {
		List<PrefixNameValue> r = new ArrayList<>();
		TLongIterator it = query.matchedKeys.iterator();
		while(it.hasNext()) {
			long l = it.next();
			PrefixNameValue pv = indexByRef.get(l);
			if (pv.addr == null && pv.poi == null) {
				loffsets.add(l);
			} else {
				r.add(pv);
			}
		}
		return r;
	}


	public PrefixNameValue addData(AddressNameIndexData from, long currentShift) {
		PrefixNameValue obj = indexByRef.get(currentShift);
		if (obj.addr != null) {
			throw new IllegalStateException(obj.toString());
		}
		obj.shift = currentShift;
		obj.addr = from;
		return obj;
	}
	
	public NameIndexReader setQuery(String qr) {
		return setQuery(qr, new NameIndexReaderMatcher(qr));
	}
	
	public NameIndexReader setQuery(String qr, NameIndexReaderMatcher matcher) {
		this.query = new NameIndexReaderQuery(qr, matcher);
		this.matchedKeys.put(qr, this.query.matchedKeys);
		return this;
	}
	
	public List<PrefixNameValue> getMatchedPrefixes(String query) {
		if (!matchedKeys.containsKey(query)) {
			return null;
		}
		List<PrefixNameValue> r = new ArrayList<>();
		for (long l : matchedKeys.get(query).toArray()) {
			PrefixNameValue pv = indexByRef.get(l);
			r.add(pv);
		}
		return r;
	}

	public void gcPrefixes(int limit) {
		if (limit > 0 && indexByRef.size() > limit) {
			indexByRef.clear();
			if (matchedKeys != null) {
				matchedKeys.clear();
			}
		}
	}
	
	public void resetBytesStat() {
		bytesStat = new NameIndexReaderBytes();
	}
	
	public NameIndexReaderBytes getBytesStat() {
		return bytesStat;
	}

	public void readTableBytes(long bytes) {
		bytesStat.readTableBytes += bytes;		
	}
	
	public void skipTableBytes(long bytes) {
		bytesStat.skipTableBytes += bytes;
	}

	public void readAtomsBytes(int bytes) {
		bytesStat.readAtomBytes += bytes;		
	}
	
	public void skipAtomsBytes(long bytes) {
		bytesStat.skipAtomBytes += bytes;		
	}
	
}
