package com.algolia.instantsearch.events;

import android.support.annotation.NonNull;

import com.algolia.instantsearch.helpers.Searcher;
import com.algolia.instantsearch.model.SearchResults;
import com.algolia.search.saas.Query;

import org.json.JSONObject;

/**
 * An event to let you react to new search results.
 */
@SuppressWarnings("WeakerAccess")
public class ResultEvent extends SearcherEvent {
    /** the search results. */
    @NonNull public final SearchResults results;
    /** the Query that was sent with the search request. */
    @NonNull public final Query query;
    /** the search request's identifier. */
    public final int requestSeqNumber;

    public static final int REQUEST_UNKNOWN = -1;

    public ResultEvent(@NonNull final Searcher searcher,
                       @NonNull final JSONObject json,
                       @NonNull final Query query,
                       final int requestSeqNumber) {
        super(searcher);
        this.results = new SearchResults(json);
        this.query = query;
        this.requestSeqNumber = requestSeqNumber;
    }

    @Override public String toString() {
        return "ResultEvent{" +
                "searcher=" + searcher +
                ", results=" + results +
                ", query=" + query +
                ", requestSeqNumber=" + requestSeqNumber +
                '}';
    }
}
