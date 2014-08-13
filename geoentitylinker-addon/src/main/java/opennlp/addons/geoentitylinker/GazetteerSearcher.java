/*
 * Copyright 2013 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package opennlp.addons.geoentitylinker;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.queryparser.classic.ParseException;

import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.Version;
import opennlp.tools.entitylinker.EntityLinkerProperties;
import org.apache.log4j.Logger;
import org.apache.lucene.analysis.util.CharArraySet;

/**
 *
 * Searches Gazetteers stored in a MMapDirectory Lucene index. The structure of
 * these indices are based on loading the indexes using the
 * GeoEntityLinkerSetupUtils
 *
 */
public class GazetteerSearcher {

  //private static final String boostedTerms = " AND loctype(ADM1^1 ADM1H^1 ADM2^1 ADM2H^1 ADM3^1 ADM3H^1 ADM4^1 ADM4H^1 ADM5^1 ADMD^1 ADMDH^1 PCLD^1 PCLH^1 PCLI^1 PCLIX^1 TERR^1 PCLIX^1 PPL^1 PPLA^1 PPLA2^1 PPLA3^1 PPLA4^1 PPLC^1 PPLCH^1 PPLF^1 PPLG^1 PPLH^1 PPLL^1 PPLQ^1 PPLR^1 PPLS^1 PPLX^1 STLMT^1) ";

  private final String REGEX_CLEAN = "[^\\p{L}\\p{Nd}]";
  private static final Logger LOGGER = Logger.getLogger(GazetteerSearcher.class);
  private double scoreCutoff = .70;
  private boolean doubleQuoteAllSearchTerms = true;
  private boolean useHierarchyField = false;
  private Directory geonamesIndex;//= new MMapDirectory(new File(indexloc));
  private IndexReader geonamesReader;// = DirectoryReader.open(geonamesIndex);
  private IndexSearcher geonamesSearcher;// = new IndexSearcher(geonamesReader);
  private Analyzer geonamesAnalyzer;
  //usgs US gazateer
  private Directory usgsIndex;//= new MMapDirectory(new File(indexloc));
  private IndexReader usgsReader;// = DirectoryReader.open(geonamesIndex);
  private IndexSearcher usgsSearcher;// = new IndexSearcher(geonamesReader);
  private Analyzer usgsAnalyzer;
  private EntityLinkerProperties properties;

  private Directory opennlpIndex;//= new MMapDirectory(new File(indexloc));
  private IndexReader opennlpReader;// = DirectoryReader.open(geonamesIndex);
  private IndexSearcher opennlpSearcher;// = new IndexSearcher(geonamesReader);
  private Analyzer opennlpAnalyzer;

  public static void main(String[] args) {
    try {
      boolean b = Boolean.valueOf("true");

      new GazetteerSearcher(new EntityLinkerProperties(new File("c:\\temp\\entitylinker.properties"))).geonamesFind("baghdad", 5, "iz");
    } catch (IOException ex) {
      java.util.logging.Logger.getLogger(GazetteerSearcher.class.getName()).log(Level.SEVERE, null, ex);
    } catch (Exception ex) {
      java.util.logging.Logger.getLogger(GazetteerSearcher.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  public GazetteerSearcher(EntityLinkerProperties properties) throws Exception {
    this.properties = properties;
    init();
  }

  /**
   * Searches the single lucene index that includes the location hierarchy.
   *
   * @param searchString the location name to search for
   * @param rowsReturned how many index entries to return (top N...)
   * @param whereClause the conditional statement that defines the index type
   * and the country oode.
   * @return
   */
  public ArrayList<GazetteerEntry> find(String searchString, int rowsReturned, String whereClause) {
    ArrayList<GazetteerEntry> linkedData = new ArrayList<>();
    searchString = cleanInput(searchString);
    if (searchString.isEmpty()) {
      return linkedData;
    }
    try {
      /**
       * build the search string Sometimes no country context is found. In this
       * case the code variables will be empty strings
       */
      String placeNameQueryString = "placename:(" + searchString.toLowerCase() + ") AND " + whereClause;
      if (searchString.trim().contains(" ") && useHierarchyField) {
        placeNameQueryString = "(placename:(" + searchString.toLowerCase() + ") AND hierarchy:(" + formatForHierarchy(searchString) + "))"
                + " AND " + whereClause;
      }
       
      /**
       * check the cache and go no further if the records already exist
       */
      ArrayList<GazetteerEntry> get = GazetteerSearchCache.get(placeNameQueryString);
      if (get != null) {

        return get;
      }
      /**
       * search the placename
       */
      QueryParser parser = new QueryParser(Version.LUCENE_48, placeNameQueryString, opennlpAnalyzer);
      Query q = parser.parse(placeNameQueryString);

      TopDocs bestDocs = opennlpSearcher.search(q, rowsReturned);

      for (int i = 0; i < bestDocs.scoreDocs.length; ++i) {
        GazetteerEntry entry = new GazetteerEntry();
        int docId = bestDocs.scoreDocs[i].doc;
        double sc = bestDocs.scoreDocs[i].score;
        entry.getScoreMap().put("lucene", sc);
        entry.setIndexID(docId + "");

        Document d = opennlpSearcher.doc(docId);

        List<IndexableField> fields = d.getFields();

        String lat = d.get("latitude");
        String lon = d.get("longitude");
        String placename = d.get("placename");
        String parentid = d.get("countrycode").toLowerCase();
        String provid = d.get("admincode");
        String itemtype = d.get("loctype");
        String source = d.get("gazsource");
        String hier = d.get("hierarchy");
        entry.setSource(source);

        entry.setItemID(docId + "");
        entry.setLatitude(Double.valueOf(lat));
        entry.setLongitude(Double.valueOf(lon));
        entry.setItemType(itemtype);
        entry.setItemParentID(parentid);
        entry.setProvinceCode(provid);
        entry.setCountryCode(parentid);
        entry.setItemName(placename);
        entry.setHierarchy(hier);
        for (int idx = 0; idx < fields.size(); idx++) {
          entry.getIndexData().put(fields.get(idx).name(), d.get(fields.get(idx).name()));
        }
        /**
         * norm the levenstein distance
         */
        int maxLen = searchString.length() > entry.getItemName().length() ? searchString.length() : entry.getItemName().length();

        Double normLev = Math.abs(1 - (sc / (double) maxLen));//searchString.length() / (double) entry.getItemName().length();
        /**
         * only want hits above the levenstein thresh. This should be a low
         * thresh due to the use of the hierarchy field in the index
         */
        if (normLev > scoreCutoff) {
          if (entry.getItemParentID().toLowerCase().equals(parentid.toLowerCase()) || parentid.toLowerCase().equals("")) {
            entry.getScoreMap().put("normlucene", normLev);
            //make sure we don't produce a duplicate
            if (!linkedData.contains(entry)) {
              linkedData.add(entry);
              /**
               * add the records to the cache for this query
               */
              GazetteerSearchCache.put(placeNameQueryString, linkedData);
            }
          }
        }
      }

    } catch (IOException | ParseException ex) {
      LOGGER.error(ex);
    }

    return linkedData;
  }

  /**
   *
   * @param searchString the named entity to look up in the lucene index
   * @param rowsReturned how many rows to allow lucene to return
   * @param code the country code
   *
   * @return
   */
  @Deprecated
  public ArrayList<GazetteerEntry> geonamesFind(String searchString, int rowsReturned, String code) {
    ArrayList<GazetteerEntry> linkedData = new ArrayList<>();
    searchString = cleanInput(searchString);
    if (searchString.isEmpty()) {
      return linkedData;
    }
    try {
      /**
       * build the search string Sometimes no country context is found. In this
       * case the code variable will be an empty string
       */
      String luceneQueryString = !code.equals("")
              ? "FULL_NAME_ND_RO:" + searchString.toLowerCase().trim() + " AND CC1:" + code.toLowerCase()//+"^90000" //[\"" + code.toLowerCase()+"\" TO \"" + code.toLowerCase() + "\"]"
              : "FULL_NAME_ND_RO:" + searchString.toLowerCase().trim();
      /**
       * check the cache and go no further if the records already exist
       */
      ArrayList<GazetteerEntry> get = GazetteerSearchCache.get(luceneQueryString);
      if (get != null) {

        return get;
      }

      QueryParser parser = new QueryParser(Version.LUCENE_48, luceneQueryString, geonamesAnalyzer);
      Query q = parser.parse(luceneQueryString);

      TopDocs search = geonamesSearcher.search(q, rowsReturned);

      for (int i = 0; i < search.scoreDocs.length; ++i) {
        GazetteerEntry entry = new GazetteerEntry();
        int docId = search.scoreDocs[i].doc;
        double sc = search.scoreDocs[i].score;

        entry.getScoreMap().put("lucene", sc);
        entry.setIndexID(docId + "");
        entry.setSource("geonames");

        Document d = geonamesSearcher.doc(docId);
        List<IndexableField> fields = d.getFields();
        for (int idx = 0; idx < fields.size(); idx++) {
          String value = d.get(fields.get(idx).name());
          value = value.toLowerCase();
          /**
           * these positions map to the required fields in the gaz TODO: allow a
           * configurable list of columns that map to the GazateerEntry fields,
           * then users would be able to plug in any gazateer they have (if they
           * build a lucene index out of it)
           */
          switch (idx) {
            case 1:
              entry.setItemID(value);
              break;
            case 3:
              entry.setLatitude(Double.valueOf(value));
              break;
            case 4:
              entry.setLongitude(Double.valueOf(value));
              break;
            case 10:
              entry.setItemType(value);
              break;
            case 12:
              entry.setItemParentID(value);
              if (!value.toLowerCase().equals(code.toLowerCase())) {
                continue;
              }
              break;
            case 23:
              entry.setItemName(value);
              break;
          }
          entry.getIndexData().put(fields.get(idx).name(), value);
        }
        /**
         * norm the levenstein distance
         */
        Double normLev = Double.valueOf(searchString.length()) / Double.valueOf(entry.getItemName().length());
        /**
         * only want hits above the levenstein thresh
         */
        if (normLev.compareTo(scoreCutoff) >= 0) {
          if (entry.getItemParentID().toLowerCase().equals(code.toLowerCase()) || code.toLowerCase().equals("")) {
            entry.getScoreMap().put("normlucene", normLev);
            //make sure we don't produce a duplicate
            if (!linkedData.contains(entry)) {
              linkedData.add(entry);
              /**
               * add the records to the cache for this query
               */
              GazetteerSearchCache.put(luceneQueryString, linkedData);
            }
          }
        }
      }

    } catch (IOException | ParseException ex) {
      LOGGER.error(ex);
    }

    return linkedData;
  }

  /**
   * Looks up the name in the USGS gazateer, after checking the cache
   *
   * @param searchString the nameed entity to look up in the lucene index
   * @param rowsReturned how many rows to allow lucene to return
   *
   * @return
   */
  @Deprecated
  public ArrayList<GazetteerEntry> usgsFind(String searchString, int rowsReturned) {
    ArrayList<GazetteerEntry> linkedData = new ArrayList<>();
    searchString = cleanInput(searchString);
    if (searchString.isEmpty()) {
      return linkedData;
    }
    String luceneQueryString = "FEATURE_NAME:" + searchString.toLowerCase().trim() + " OR MAP_NAME: " + searchString.toLowerCase().trim();
    try {

      /**
       * hit the cache
       */
      ArrayList<GazetteerEntry> get = GazetteerSearchCache.get(luceneQueryString);
      if (get != null) {
        //if the name is already there, return the list of cavhed results
        return get;
      }
      QueryParser parser = new QueryParser(Version.LUCENE_48, luceneQueryString, usgsAnalyzer);
      Query q = parser.parse(luceneQueryString);

      TopDocs search = usgsSearcher.search(q, rowsReturned);
      for (int i = 0; i < search.scoreDocs.length; i++) {
        GazetteerEntry entry = new GazetteerEntry();
        int docId = search.scoreDocs[i].doc;
        double sc = search.scoreDocs[i].score;
        //keep track of the min score for normalization

        entry.getScoreMap().put("lucene", sc);
        entry.setIndexID(docId + "");
        entry.setSource("usgs");
        entry.setItemParentID("us");
        Document d = usgsSearcher.doc(docId);
        List<IndexableField> fields = d.getFields();
        for (int idx = 0; idx < fields.size(); idx++) {
          String value = d.get(fields.get(idx).name());
          value = value.toLowerCase();
          switch (idx) {
            case 0:
              entry.setItemID(value);
              break;
            case 1:
              entry.setItemName(value);
              break;
            case 2:
              entry.setItemType(value);
              break;
            case 9:
              entry.setLatitude(Double.valueOf(value));
              break;
            case 10:
              entry.setLongitude(Double.valueOf(value));
              break;
          }
          entry.getIndexData().put(fields.get(idx).name(), value);
        }
        /**
         * norm the levenstein distance
         */
        Double normLev = Double.valueOf(searchString.length()) / Double.valueOf(entry.getItemName().length());
        /**
         * only want hits above the levenstein thresh
         */
        if (normLev.compareTo(scoreCutoff) >= 0) {
          //only keep it if the country code is a match. even when the code is passed in as a weighted condition, there is no == equiv in lucene

          entry.getScoreMap().put("normlucene", normLev);
          //make sure we don't produce a duplicate
          if (!linkedData.contains(entry)) {
            linkedData.add(entry);
            /**
             * add the records to the cache for this query
             */
            GazetteerSearchCache.put(luceneQueryString, linkedData);
          }
        }

      }

    } catch (IOException | ParseException ex) {
      LOGGER.error(ex);
    }

    return linkedData;
  }

  /**
   * Replaces any noise chars with a space, and depending on configuration adds
   * double quotes to the string
   *
   * @param input
   * @return
   */
  private String cleanInput(String input) {
    String output = input.replaceAll(REGEX_CLEAN, " ").trim();
    output = output.replace("  ", " ");
    if (doubleQuoteAllSearchTerms) {
      return "\"" + output + "\"";
    } else {
      return output;
    }

  }

  private void init() throws Exception {

    if (opennlpIndex == null) {
      String indexloc = properties.getProperty("opennlp.geoentitylinker.gaz", "");
      if (indexloc.equals("")) {
        LOGGER.error(new Exception("Opennlp combined Gaz directory location not found"));

      }

      opennlpIndex = new MMapDirectory(new File(indexloc));
      opennlpReader = DirectoryReader.open(opennlpIndex);
      opennlpSearcher = new IndexSearcher(opennlpReader);
      //TODO: a language code switch statement should be employed here at some point
      opennlpAnalyzer = new StandardAnalyzer(Version.LUCENE_48, new CharArraySet(Version.LUCENE_48, new ArrayList(), true));
      String cutoff = properties.getProperty("opennlp.geoentitylinker.gaz.lucenescore.min", String.valueOf(scoreCutoff));
      String usehierarchy = properties.getProperty("opennlp.geoentitylinker.gaz.hierarchyfield", String.valueOf("0"));
      if (cutoff != null && !cutoff.isEmpty()) {
        scoreCutoff = Double.valueOf(cutoff);
      }
      if (usehierarchy != null && !usehierarchy.isEmpty()) {
        useHierarchyField = Boolean.valueOf(usehierarchy);
      }
      //  opennlp.geoentitylinker.gaz.doublequote=false
      //opennlp.geoentitylinker.gaz.hierarchyfield=false

    }
  }

  private String formatForHierarchy(String searchTerm) {
    String[] parts = cleanInput(searchTerm).split(" ");
    String out = "";
    if (parts.length != 0) {
      for (String string : parts) {
        out += string + " AND ";
      }
      out = out.substring(0, out.lastIndexOf(" AND "));
    } else {
      out = cleanInput(searchTerm);
    }
    return out;
  }

}
