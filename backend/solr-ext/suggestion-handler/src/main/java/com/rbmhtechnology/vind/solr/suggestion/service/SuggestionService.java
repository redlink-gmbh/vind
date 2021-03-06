package com.rbmhtechnology.vind.solr.suggestion.service;

import com.rbmhtechnology.vind.solr.suggestion.SuggestionRequestHandler;
import com.rbmhtechnology.vind.solr.suggestion.jsonfacetmodel.Pivot;
import com.rbmhtechnology.vind.solr.suggestion.params.SuggestionRequestParams;
import com.rbmhtechnology.vind.solr.suggestion.params.SuggestionResultParams;
import com.rbmhtechnology.vind.solr.suggestion.result.SuggesionResultSingle;
import com.rbmhtechnology.vind.solr.suggestion.result.SuggestionResult;
import com.rbmhtechnology.vind.solr.suggestion.result.SuggestionResultFactory;
import com.rbmhtechnology.vind.solr.suggestion.result.SuggestionResultMulti;
import org.apache.commons.lang.StringUtils;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.FacetParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.component.SearchHandler;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * This suggestion service queries a given core for facet suggestions based on an input string
 * <p/>
 * Author: Thomas Kurz (tkurz@apache.org)
 */
public class SuggestionService {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private static final String IGNORE_CASE_REGEX = "[%s|%s]";
    private static final String PREFIX_REGEX = "/(%s.*)|(.* +%s.*)/";
    private static final String INTERVAL_QUERY = "%s:[%s TO %s]";
    private static final Collection<String> SOLR_REGEX_ESCAPE_CHARS = Arrays.asList("-",".","*","+");

    private String internalFacetLimit = "50";

    private boolean spellcheck_enabled = false;

    private SolrCore solrCore;

    private SearchHandler searchHandler;

    public SuggestionService(SolrCore solrCore, NamedList args) {

        NamedList l = new NamedList();

        //set spellcheck component if there is one
        if(((ArrayList)args.get("first-components")).contains("spellcheck")) {
            List component = new ArrayList<String>();
            component.add("spellcheck");
            l.add("first-components",component);
            spellcheck_enabled = true;
        }

        if(args.get("defaults") != null && ((NamedList)args.get("defaults")).get(SuggestionRequestParams.SUGGESTION_INTERNAL_LIMIT) != null) {
            internalFacetLimit = (String)((NamedList)args.get("defaults")).get(SuggestionRequestParams.SUGGESTION_INTERNAL_LIMIT);
        }

        this.solrCore = solrCore;
        this.searchHandler = new SearchHandler();
        this.searchHandler.init(l);
        this.searchHandler.inform(solrCore);
    }

    public void run(SolrQueryResponse rsp, SolrParams params, String query, String df, String[] fields, String[] singlevalue_fields, String[] multivalue_fields, String[] fqs, int termLimit, int limit, SuggestionRequestHandler.LimitType limitType, SuggestionRequestHandler.Type type, SuggestionRequestHandler.Strategy strategy, String suggestionField, Map<String, Map<String,Object>> intervals) {

        //analyze query in advance
        query = FieldAnalyzerService.analyzeString(solrCore, df, query);

        SuggestionResult[] result = null;

        Object spellcheck_result;

        SolrQueryResponse response = query(query,params,df,fields,fqs,termLimit,suggestionField,intervals);

        if(response.getValues().get("facets") instanceof SimpleOrderedMap) {
            SimpleOrderedMap facets = (SimpleOrderedMap) response.getValues().get("facets");
            if((Integer) facets.get("count") > 0) {
                result = createResults(response, singlevalue_fields, multivalue_fields, query, df,type,termLimit, limit, limitType, strategy,suggestionField,intervals);
            }
        } else {
            SolrDocumentList doclist = (SolrDocumentList) response.getValues().get("facet");
            if(doclist.getNumFound() > 0) {
                result = createResults(response, singlevalue_fields, multivalue_fields, query, df,type,termLimit, limit, limitType, strategy,suggestionField,intervals);
            }
        }

        //if no results, try spellcheck (if defined and if spellchecked query differs from original)
        if(result == null && spellcheck_enabled) {
            String spellchecked_query = getSpellcheckedQuery(response);
            spellcheck_result = response.getValues().get("spellcheck");

            //query with spellchecked query
            if(spellchecked_query != null) {
                SolrQueryResponse spellcheck_response = query(spellchecked_query,params,df,fields,fqs,termLimit,suggestionField,intervals);
                if(spellcheck_response.getValues().get("facets") instanceof SimpleOrderedMap) {
                    SimpleOrderedMap facets = (SimpleOrderedMap) spellcheck_response.getValues().get("facets");
                    if((Integer) facets.get("count") > 0) {
                        result = createResults(spellcheck_response, singlevalue_fields, multivalue_fields, spellchecked_query, df,type, termLimit, limit, limitType, strategy,suggestionField,intervals);
                    }
                } else {
                    SolrDocumentList doclist = (SolrDocumentList) spellcheck_response.getValues().get("facet");
                    if(doclist.getNumFound() > 0) {
                        result = createResults(spellcheck_response, singlevalue_fields, multivalue_fields, spellchecked_query, df,type, termLimit, limit, limitType, strategy,suggestionField,intervals);
                    }
                }
                //add result of spellcheck component
                if(spellcheck_result != null && result != null) {
                    //TODO remove * on last position of collation
                    rsp.add("spellcheck",spellcheck_result);
                }
            }
        }

        if(result == null) {
            result = createEmptyResults(type,limit, limitType);
        }

        if(result[0] != null) rsp.add(SuggestionResultParams.SUGGESTIONS, result[0].write());
        if(result[1] != null) rsp.add(SuggestionResultParams.MULTI_SUGGESTIONS, result[1].write());
    }

    private SuggestionResult[] createEmptyResults(SuggestionRequestHandler.Type type, int limit, SuggestionRequestHandler.LimitType limitType) {
        SuggestionResult[] result = new SuggestionResult[2];
        switch (type) {
            case single:
                result[0] = new SuggesionResultSingle(limit,limitType);break;
            case multi:
                result[1] = new SuggestionResultMulti(limit,limitType);break;
            case mixed:
                result[0] = new SuggesionResultSingle(limit,limitType);
                result[1] = new SuggestionResultMulti(limit,limitType);
        }
        return result;
    }

    protected SuggestionResult[] createResults(SolrQueryResponse rsp, String[] singlevalue_fields, String[] multivalue_fields, String query, String df, SuggestionRequestHandler.Type type, int termLimit, int limit, SuggestionRequestHandler.LimitType limitType, SuggestionRequestHandler.Strategy strategy, String sugestionField, Map<String, Map<String,Object>> intervals) {
        SuggestionResult[] result = new SuggestionResult[2];
        switch (type) {
            case single:
                result[0] = SuggestionResultFactory.createSingleValueResult(solrCore, rsp, singlevalue_fields, query, df, termLimit, limit, limitType, strategy,sugestionField,intervals);
                break;
            case multi:
                result[1] = SuggestionResultFactory.createMultiValueResult(solrCore, rsp, multivalue_fields, query, df,termLimit, limit, limitType); //TODO consider strategy
                break;
            case mixed:
                result[0] = SuggestionResultFactory.createSingleValueResult(solrCore, rsp, singlevalue_fields, query, df,termLimit, limit, limitType, strategy,sugestionField,intervals);
                result[1] = SuggestionResultFactory.createMultiValueResult(solrCore, rsp, multivalue_fields, query, df, termLimit, limit, limitType);
        }

        if ((result[0] == null || result[0].getCount() ==0) && (result[1] == null || ((SuggestionResultMulti) result[1]).getCount() == 0)) {
            result = null;
        }
        return result;
    }

    private String getSpellcheckedQuery(SolrQueryResponse rsp) {

        //check if spellcheck result exists.
        if(rsp.getValues().get("spellcheck") == null) return null;

        NamedList collations = (NamedList)((NamedList)rsp.getValues().get("spellcheck")).get("collations");

        if(collations != null && collations.size() > 0) {
            String s = (String)collations.get("collation");
            return s.substring(0,s.length()-1);
        } else {
            String s = (String)((NamedList)((NamedList)rsp.getValues().get("spellcheck")).get("suggestions")).get("collation");
            return s != null ? s.substring(0,s.length()-1) : null;
        }
    }

    private SolrQueryResponse query(String query, SolrParams original_params, String df, String[] fields, String[] fqs, int termLimit, String suggestionField, Map<String,Map<String,Object>> intervals) {

        SolrQueryResponse rsp = new SolrQueryResponse();

        //remove *
        if(query.contains("*")) {
            query = query.replaceAll("\\*", "");
        }
        //Split the query into terms separated by spaces
        List<String> terms = Arrays.asList(query.trim().split(" |\\+"));

        //Check if the number of terms in the query is bigger than the suggestion.term.limit
        if(terms.size() > termLimit) {
            terms = terms.subList(0,termLimit);
        }

        //Get the REGEX expression for each term to make them match as prefix in any word of a field.
        List<String> queryPreffixes = terms.stream()
                .map(term -> term.chars()
                        .mapToObj(i -> (char)i)
                        .map(letter -> {
                            //Escaping regex special characters
                            final String str = SOLR_REGEX_ESCAPE_CHARS.contains(letter.toString())?
                                    "\\" + letter.toString(): letter.toString();
                            return  String.format(IGNORE_CASE_REGEX, letter, StringUtils.upperCase(str));
                        })
                        .collect(Collectors.joining()))
                .map(prefix -> String.format(PREFIX_REGEX, prefix, prefix))
                .collect(Collectors.toList());

        log.debug("original query params: {}", original_params);

        //Prepare query
        ModifiableSolrParams params = new ModifiableSolrParams();

        //add original params
        //params.add(original_params);

        //add other params
        SolrQueryRequest req = new LocalSolrQueryRequest( solrCore, params );
        params.set(CommonParams.Q, "*:*");
        params.set(CommonParams.DF, df);
        params.set("q.op", "AND");
        params.set(FacetParams.FACET, "true");

        //clean param lists
        params.remove(FacetParams.FACET_FIELD);
        params.remove(CommonParams.FQ);
        params.remove("spellcheck");
        params.remove("spellcheck.collate");

        final ArrayList<String> queryRegex = new ArrayList<>();
        final Map<String,Object> filterMapNamed = new HashMap<>();

        //////////////////
        //Filed facets definition
        //////////////////
        for(String field : fields) {
            //preparing the prefix query for each search term
            final List<String> fieldQuery = queryPreffixes.stream().
                    map(prefix -> String.join(":", field, prefix)).
                    collect(Collectors.toList());

            //Joining different search terms regex
            final String fieldQueryRegex = String.join(" ", fieldQuery);

            //Settings for the field term facet
            final Map<String,Object> fieldMap = new HashMap<>();
            fieldMap.put("type", Pivot.facetType.terms.name());
            fieldMap.put("field",field);
            fieldMap.put("limit",Integer.valueOf(internalFacetLimit));
            fieldMap.put("mincount",1);

            final Map<String,Object> fieldMapNamed = new HashMap<>();
            fieldMapNamed.put(field,fieldMap);

            //Settings for the field filter facet
            final Map<String,Object> filterMap = new HashMap<>();
            filterMap.put("type", Pivot.facetType.query.name());
            filterMap.put("q",fieldQueryRegex);
            filterMap.put("facet",fieldMapNamed);

            filterMapNamed.put(field.concat("_filter"),filterMap);

            queryRegex.add(fieldQueryRegex);
        }

        //////////////////
        //Adding Intervals
        //////////////////
        final Map<String,Object> intervalsJson = new HashMap<>();
        if(original_params.getBool(SuggestionRequestParams.SUGGESTION_INTERVAL,false) && !intervals.isEmpty()){
            intervals.keySet().forEach(intervalKey -> {
                final Map<String, Object> intervalMap = intervals.get(intervalKey);
                final Map<String,Object> intervalJson = new HashMap<>();
                intervalJson.put("type", Pivot.facetType.query.name());
                intervalJson.put("q", String.format(INTERVAL_QUERY, suggestionField, intervalMap.get("start"), intervalMap.get("end")));
                intervalJson.put("facet",filterMapNamed);

                intervalsJson.put(intervalKey, intervalJson);
            });

        }
        //Filtering here provides smaller response but takes more time, better skip this general query
        //final String prefixSolrQuery = String.join(" OR ", queryRegex.stream().map(term -> "("+term+")").collect(Collectors.toList()));
        //params.set(CommonParams.Q, prefixSolrQuery);

        //Transcend previous filter queries
        if(fqs != null) {
            for(String fq : fqs) {
                params.add(CommonParams.FQ,fq);
            }
        }

        if(spellcheck_enabled) {
            params.add("spellcheck","true");
            params.add("spellcheck.q",String.join(" ",terms).concat("*") );
            params.add("spellcheck.collate","true");
            final String accuarcy = original_params.get("spellcheck.accuracy");
            if(Objects.nonNull(accuarcy)) {
                params.add("spellcheck.accuracy", accuarcy);
            }
        }

        final Map<String,Object> jsonFacet = new HashMap<>();
        if(original_params.getBool(SuggestionRequestParams.SUGGESTION_INTERVAL, false) && !intervalsJson.isEmpty()){
            jsonFacet.put("facet",intervalsJson);
            req.setJSON(jsonFacet);
        } else {
            jsonFacet.put("facet", filterMapNamed);
            req.setJSON(jsonFacet);
        }

        try {
            log.info("internal request: {}", req.toString());
            log.info("JSON facet query: {}", req.getJSON().toString());
            //execute query and return
            final long millis = new DateTime().getMillis();
            searchHandler.handleRequestBody(req, rsp);
            log.info("Internal query for suggestions took a Total time of: {}ms",  new DateTime().getMillis() - millis);
            log.debug("internal response: {}", rsp.getValues().toString());
            return rsp;
        } catch (SolrException e) {
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,"internal server error");
        } finally {
            req.close();
        }
    }

}
