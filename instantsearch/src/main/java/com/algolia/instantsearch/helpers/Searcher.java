package com.algolia.instantsearch.helpers;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.SparseArray;

import com.algolia.instantsearch.BuildConfig;
import com.algolia.instantsearch.events.CancelEvent;
import com.algolia.instantsearch.events.ErrorEvent;
import com.algolia.instantsearch.events.ResultEvent;
import com.algolia.instantsearch.events.SearchEvent;
import com.algolia.instantsearch.model.AlgoliaResultsListener;
import com.algolia.instantsearch.model.FacetStat;
import com.algolia.instantsearch.model.NumericRefinement;
import com.algolia.instantsearch.model.SearchResults;
import com.algolia.search.saas.AlgoliaException;
import com.algolia.search.saas.Client;
import com.algolia.search.saas.CompletionHandler;
import com.algolia.search.saas.Index;
import com.algolia.search.saas.Query;
import com.algolia.search.saas.Request;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@SuppressWarnings("UnusedReturnValue") // chaining
public class Searcher {
    private static final List<Searcher> instances = new ArrayList<>();
    private final int id;

    private final EventBus bus;
    private Index index;
    private final Client client;
    private Query query;

    private final List<AlgoliaResultsListener> resultsListeners = new ArrayList<>();

    private static int lastSearchSeqNumber; // Identifier of last fired query
    private int lastDisplayedSeqNumber; // Identifier of last displayed query
    private int lastRequestedPage;
    private int lastDisplayedPage;
    private boolean endReached;

    private final List<String> disjunctiveFacets = new ArrayList<>();
    private final Map<String, List<String>> refinementMap = new HashMap<>();
    private final Map<String, SparseArray<NumericRefinement>> numericRefinements = new HashMap<>();
    private final Map<String, Boolean> booleanFilterMap = new HashMap<>();

    private final List<String> facets = new ArrayList<>();

    private final SparseArray<Request> pendingRequests = new SparseArray<>();

    /**
     * Create and initialize the helper.
     *
     * @param index an Index initialized and eventually configured.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public Searcher(@NonNull final Index index) {
        this.index = index;
        this.client = index.getClient();
        query = new Query();
        client.addUserAgent(new Client.LibraryVersion("InstantSearch Android", String.valueOf(BuildConfig.VERSION_NAME)));
        bus = EventBus.getDefault();
        id = instances.size();
        instances.add(this);
    }

    /**
     * Create and initialize the helper.
     *
     * @param appId     Your Algolia Application ID.
     * @param apiKey    A search-only API Key. (never use API keys that could modify your records! see https://www.algolia.com/doc/guides/security/api-keys)
     * @param indexName An index to target.
     */
    public Searcher(@NonNull final String appId, @NonNull final String apiKey, @NonNull final String indexName) {
        this(new Client(appId, apiKey).getIndex(indexName));
    }

    @Deprecated //DISCUSS: Should we expose this?
    public static Searcher get(int id) {
        return instances.get(id);
    }

    /**
     * Start a search with the given text.
     *
     * @param queryString a String to search on the index.
     */
    @NonNull
    public Searcher search(final String queryString) {
        query.setQuery(queryString);
        search();
        return this;
    }

    /**
     * Start a search with the current helper's state.
     */
    @NonNull
    public Searcher search() {
        endReached = false;
        lastRequestedPage = 0;
        lastDisplayedPage = -1;
        final int currentSearchSeqNumber = ++lastSearchSeqNumber;

        bus.post(new SearchEvent(query, currentSearchSeqNumber));
        final CompletionHandler searchHandler = new CompletionHandler() {
            @Override
            public void requestCompleted(@Nullable JSONObject content, @Nullable AlgoliaException error) {
                pendingRequests.remove(currentSearchSeqNumber);
                // NOTE: Canceling any request anterior to the current one.
                //
                // Rationale: Although TCP imposes a server to send responses in the same order as
                // requests, nothing prevents the system from opening multiple connections to the
                // same server, nor the Algolia client to transparently switch to another server
                // between two requests. Therefore the order of responses is not guaranteed.
                for (int i = 0; i < pendingRequests.size(); i++) {
                    int reqId = pendingRequests.keyAt(i);
                    Request request = pendingRequests.valueAt(i);
                    if (reqId < currentSearchSeqNumber) {
                        cancelRequest(request, reqId);
                    }
                }

                if (currentSearchSeqNumber <= lastDisplayedSeqNumber) {
                    Log.e("Algolia|Searcher", "We already displayed results for request " + lastDisplayedSeqNumber + ", current request (" + currentSearchSeqNumber + ") should have been canceled");
                }

                if (content == null || !hasHits(content)) {
                    endReached = true;
                } else {
                    checkIfLastPage(content);
                }

                lastDisplayedSeqNumber = currentSearchSeqNumber;
                lastDisplayedPage = 0;

                if (error != null) {
                    bus.post(new ErrorEvent(error, query, currentSearchSeqNumber));
                    for (AlgoliaResultsListener view : resultsListeners) {
                        view.onError(query, error);
                    }
                } else {
                    bus.post(new ResultEvent(content, query, currentSearchSeqNumber));
                    updateListeners(content, false);
                }
            }
        };

        final Request searchRequest;
        if (disjunctiveFacets.size() != 0) {
            searchRequest = index.searchDisjunctiveFacetingAsync(query, disjunctiveFacets, refinementMap, searchHandler);
        } else {
            searchRequest = index.searchAsync(query, searchHandler);
        }
        pendingRequests.put(currentSearchSeqNumber, searchRequest);
        return this;
    }

    private void cancelRequest(Request request, Integer requestSeqNumber) {
        if (!request.isCancelled()) {
            request.cancel();
            bus.post(new CancelEvent(request, requestSeqNumber));
            pendingRequests.delete(requestSeqNumber);
        } else {
            throw new IllegalStateException("cancelRequest was called on a request that was already canceled.");
        }
    }

    /**
     * Load more results with the same query.
     * Note that this method won't do anything if {@link Searcher#shouldLoadMore} returns false.
     */
    @NonNull
    public Searcher loadMore() {
        if (!shouldLoadMore()) {
            return this;
        }
        Query loadMoreQuery = new Query(query);
        loadMoreQuery.setPage(++lastRequestedPage);
        final int currentSearchSeqNumber = ++lastSearchSeqNumber;
        bus.post(new SearchEvent(query, currentSearchSeqNumber));
        pendingRequests.put(currentSearchSeqNumber, index.searchAsync(loadMoreQuery, new CompletionHandler() {
            @Override
            public void requestCompleted(@NonNull JSONObject content, @Nullable AlgoliaException error) {
                pendingRequests.remove(currentSearchSeqNumber);
                if (error != null) {
                    bus.post(new ErrorEvent(error, query, currentSearchSeqNumber));
                    for (AlgoliaResultsListener view : resultsListeners) {
                        view.onError(query, error);
                    }
                } else {
                    if (currentSearchSeqNumber <= lastDisplayedSeqNumber) {
                        return; // Hits are for an older query, let's ignore them
                    }

                    bus.post(new ResultEvent(content, query, currentSearchSeqNumber));
                    if (hasHits(content)) {
                        updateListeners(content, true);
                        lastDisplayedPage = lastRequestedPage;

                        checkIfLastPage(content);
                    } else {
                        endReached = true;
                    }
                }
            }
        }));
        return this;
    }

    private void checkIfLastPage(@NonNull JSONObject content) {
        if (content.optInt("nbPages") == content.optInt("page") + 1) {
            endReached = true;
        }
    }

    /**
     * Tell if we should load more hits when reaching the end of an {@link AlgoliaResultsListener}.
     *
     * @return {@code true} unless we reached the end of hits or we already requested a new page.
     */
    public boolean shouldLoadMore() {
        return !(endReached || lastRequestedPage > lastDisplayedPage);
    }

    /**
     * Reset the helper's state.
     */
    @NonNull
    public Searcher reset() {
        lastDisplayedPage = 0;
        lastRequestedPage = 0;
        lastDisplayedSeqNumber = 0;
        endReached = false;
        clearFacetRefinements();
        cancelPendingRequests();
        numericRefinements.clear();
        return this;
    }

    /**
     * Checks if some requests are still waiting for a response.
     *
     * @return true if there is at least one pending request.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public boolean hasPendingRequests() {
        return pendingRequests.size() != 0;
    }

    /**
     * Cancels all requests still waiting for a response.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public Searcher cancelPendingRequests() {
        if (pendingRequests.size() != 0) {
            for (int i = 0; i < pendingRequests.size(); i++) {
                int reqId = pendingRequests.keyAt(i);
                Request r = pendingRequests.valueAt(i);
                if (!r.isFinished() && !r.isCancelled()) {
                    cancelRequest(r, reqId);
                }
            }
        }
        return this;
    }

    /**
     * Add a facet refinement for the next queries.
     *
     * @param attributeName      the facet name.
     * @param isDisjunctiveFacet if {@code true}, the facet will be added as a disjunctive facet.
     * @param values             an eventual list of values to refine on.
     */
    public void addFacet(@NonNull String attributeName, boolean isDisjunctiveFacet, @Nullable ArrayList<String> values) {
        if (isDisjunctiveFacet) {
            disjunctiveFacets.add(attributeName);
        }
        if (values == null) {
            values = new ArrayList<>();
        }
        refinementMap.put(attributeName, values);
    }

    /**
     * Add or remove this facet according to its enabled status.
     *
     * @param attributeName the attribute to facet on.
     * @param value         the value for this attribute.
     * @param active        {@code true} if this facet value is currently refined on.
     */
    @NonNull
    public Searcher updateFacetRefinement(@NonNull String attributeName, @NonNull String value, boolean active) {
        if (active) {
            addFacetRefinement(attributeName, value);
        } else {
            removeFacetRefinement(attributeName, value);
        }
        return this;
    }


    /**
     * Add a facet refinement for the next queries.
     * This method resets the current page to 0.
     *
     * @param attributeName the attribute to refine on.
     * @param value         the facet's value to refine with.
     */
    @NonNull
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public Searcher addFacetRefinement(@NonNull String attributeName, @NonNull String value) {
        List<String> attributeRefinements = refinementMap.get(attributeName);
        if (attributeRefinements == null) {
            attributeRefinements = new ArrayList<>();
            refinementMap.put(attributeName, attributeRefinements);
        }
        attributeRefinements.add(value);
        rebuildQueryFacetFilters();
        return this;
    }

    /**
     * Remove a facet refinement for the next queries.
     * This method resets the current page to 0.
     *
     * @param attributeName the attribute to refine on.
     * @param value         the facet's value to refine with.
     */
    @NonNull
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public Searcher removeFacetRefinement(@NonNull String attributeName, @NonNull String value) {
        List<String> attributeRefinements = refinementMap.get(attributeName);
        if (attributeRefinements == null) {
            attributeRefinements = new ArrayList<>();
            refinementMap.put(attributeName, attributeRefinements);
        } else {
            attributeRefinements.remove(value);
        }
        rebuildQueryFacetFilters();
        return this;
    }

    /**
     * Check if a facet refinement is enabled.
     *
     * @param attributeName the attribute to refine on.
     * @param value         the facet's value to check.
     * @return {@code true} if {@code attributeName} is being refined with {@code value}.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public boolean hasFacetRefinement(@NonNull String attributeName, @NonNull String value) {
        List<String> attributeRefinements = refinementMap.get(attributeName);
        return attributeRefinements != null && attributeRefinements.contains(value);
    }

    /**
     * Clear all facet refinements for the next queries.
     * This method resets the current page to 0.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public Searcher clearFacetRefinements() {
        refinementMap.clear();
        disjunctiveFacets.clear();
        rebuildQueryFacetFilters();
        return this;
    }


    /**
     * Clear an attribute's facet refinements for the next queries.
     * This method resets the current page to 0.
     *
     * @param attribute the attribute's name.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public Searcher clearFacetRefinements(@NonNull String attribute) {
        final List<String> stringList = refinementMap.get(attribute);
        if (stringList != null) {
            stringList.clear();
        }
        disjunctiveFacets.remove(attribute);
        rebuildQueryFacetFilters();
        return this;
    }

    private Searcher rebuildQueryFacetFilters() {
        JSONArray facetFilters = new JSONArray();
        for (Map.Entry<String, List<String>> entry : refinementMap.entrySet()) {
            final List<String> values = entry.getValue();
            final String attribute = entry.getKey();

            for (String value : values) {
                facetFilters.put(attribute + ":" + value);
            }
        }
        for (Map.Entry<String, Boolean> entry : booleanFilterMap.entrySet()) {
            facetFilters.put(entry.getKey() + ":" + entry.getValue());
        }
        //noinspection deprecation Deprecated for app developers
        query.setFacetFilters(facetFilters);
        query.setPage(0);
        return this;
    }

    /**
     * Get the current numeric refinement for an attribute and an operator.
     *
     * @param attribute the attribute to refine on.
     * @param operator  one of the {@link NumericRefinement#OPERATOR_EQ operators} defined in {@link NumericRefinement}.
     * @return a {@link NumericRefinement} describing the current refinement for these parameters, or {@code null} if there is none.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public @Nullable NumericRefinement getNumericRefinement(@NonNull String attribute, int operator) {
        NumericRefinement.checkOperatorIsValid(operator);
        final SparseArray<NumericRefinement> attributeRefinements = numericRefinements.get(attribute);
        return attributeRefinements == null ? null : attributeRefinements.get(operator);
    }

    /**
     * Add a numeric refinement.
     *
     * @param refinement a {@link NumericRefinement} refining an attribute with a numerical value.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public Searcher addNumericRefinement(@NonNull NumericRefinement refinement) {
        SparseArray<NumericRefinement> refinements = numericRefinements.get(refinement.attribute);
        if (refinements == null) {
            refinements = new SparseArray<>();
        }
        refinements.put(refinement.operator, refinement);
        numericRefinements.put(refinement.attribute, refinements);
        rebuildQueryNumericFilters();
        return this;
    }

    /**
     * Remove any numeric refinements relative to a specific attribute.
     *
     * @param attribute the attribute that may have a refinement.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public Searcher removeNumericRefinement(@NonNull String attribute) {
        numericRefinements.remove(attribute);
        rebuildQueryNumericFilters();
        return this;
    }


    /**
     * Remove the numeric refinement for a specific attribute and operator.
     *
     * @param attribute an attribute that maybe has some refinements.
     * @param operator  an {@link NumericRefinement#OPERATOR_EQ operator}.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public Searcher removeNumericRefinement(@NonNull String attribute, int operator) {
        NumericRefinement.checkOperatorIsValid(operator);
        numericRefinements.get(attribute).remove(operator);
        rebuildQueryNumericFilters();
        return this;
    }

    /**
     * Remove the numeric refinement for a specific attribute and operator.
     *
     * @param refinement a description of the refinement to remove.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public Searcher removeNumericRefinement(@NonNull NumericRefinement refinement) {
        NumericRefinement.checkOperatorIsValid(refinement.operator);
        numericRefinements.get(refinement.attribute).remove(refinement.operator);
        rebuildQueryNumericFilters();
        return this;
    }

    private void rebuildQueryNumericFilters() {
        JSONArray numericFilters = new JSONArray();
        for (SparseArray<NumericRefinement> refinements : numericRefinements.values()) {
            for (int i = 0; i < refinements.size(); i++) {
                numericFilters.put(refinements.valueAt(i).toString());
            }
        }
        query.setNumericFilters(numericFilters);
        query.setPage(0);
    }


    /**
     * Add a boolean refinement.
     *
     * @param attribute the attribute to refine on.
     * @param value     the value to refine with.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public Searcher addBooleanFilter(String attribute, Boolean value) {
        booleanFilterMap.put(attribute, value);
        rebuildQueryFacetFilters();
        return this;
    }

    /**
     * Get the current boolean refinement for an attribute.
     *
     * @param attribute the attribute that may have a refinement.
     * @return the refinement value, or {@code null} if there is none.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public @Nullable Boolean getBooleanFilter(String attribute) {
        return booleanFilterMap.get(attribute);
    }

    /**
     * Remove any boolean refinement for an attribute.
     *
     * @param attribute the attribute that may have a refinement.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public Searcher removeBooleanFilter(String attribute) {
        booleanFilterMap.remove(attribute);
        rebuildQueryFacetFilters();
        return this;
    }

    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public Searcher addFacet(String... attributes) {
        Collections.addAll(facets, attributes);
        rebuildQueryFacets();
        return this;
    }

    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public Searcher removeFacet(String... attributes) {
        //TODO: Count calls to add() and remove only if last one
        for (String attribute : attributes) {
            facets.remove(attribute);
        }
        rebuildQueryFacets();
        return this;
    }

    private Searcher rebuildQueryFacets() {
        final String[] facetArray = this.facets.toArray(new String[this.facets.size()]);
        query.setFacets(facetArray);
        return this;
    }

    @Deprecated
    public Searcher registerListener(@NonNull AlgoliaResultsListener resultsListener) {
        if (!resultsListeners.contains(resultsListener)) {
            resultsListeners.add(resultsListener);
        }
        return this;
    }

    private void updateListeners(@Nullable JSONObject hits, boolean isLoadingMore) {
        for (AlgoliaResultsListener view : resultsListeners) {
            view.onResults(new SearchResults(hits), isLoadingMore);
        }
    }

    /**
     * Find if a returned json contains at least one hit.
     *
     * @param jsonObject the query result.
     * @return {@code true} if it contains a hits array with at least one non null element.
     */
    static boolean hasHits(@Nullable JSONObject jsonObject) {
        if (jsonObject == null) {
            return false;
        }

        JSONArray resultHits = jsonObject.optJSONArray("hits");
        if (resultHits == null) {
            return false;
        }

        for (int i = 0; i < resultHits.length(); ++i) {
            JSONObject hit = resultHits.optJSONObject(i);
            if (hit != null) {
                return true;
            }
        }
        return false;
    }

    public Query getQuery() {
        return query;
    }

    /**
     * Use the given query's parameters for following search queries.
     *
     * @param query a {@link Query} object with some parameters set.
     */
    @NonNull
    public Searcher setQuery(@NonNull Query query) {
        this.query = query;
        this.query.setPage(0);
        return this;
    }

    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    //TODO: Document every public method
    public Index getIndex() {
        return index;
    }

    /**
     * Change the targeted index for future queries.
     * Be aware that as index ordering may differ, this method will reset the current page to 0,
     * You may want to use {@link Searcher#reset} to reinitialize the helper to an empty state.
     *
     * @param indexName name of the new index.
     */
    @SuppressWarnings({"WeakerAccess", "unused"}) // For library users
    public @NonNull Searcher setIndex(@NonNull String indexName) {
        index = client.getIndex(indexName);
        query.setPage(0);
        return this;
    }

    @Deprecated //DISCUSS: Should we expose this?
    public int getId() {
        return id;
    }
}
