---
navigation_title: "more_like_this"
mapped_pages:
  - https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-mlt-query.html
---

# more_like_this query [query-dsl-mlt-query]


The `more_like_this` query finds documents that are "like" a given set of documents. To do so, MLT selects a set of representative terms of these input documents, forms a query using these terms, executes the query and returns the results. The user controls the input documents, how the terms should be selected and how the query is formed.

The simplest use case consists of asking for documents that are similar to a provided piece of text. Here, we are asking for all movies that have some text similar to "Once upon a time" in their "title" and in their "description" fields, limiting the number of selected terms to 12.

```console
GET /_search
{
  "query": {
    "more_like_this" : {
      "fields" : ["title", "description"],
      "like" : "Once upon a time",
      "min_term_freq" : 1,
      "max_query_terms" : 12
    }
  }
}
```

A more complicated use case consists of mixing texts with documents already existing in the index. In this case, the syntax to specify a document is similar to the one used in the [Multi GET API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-mget).

```console
GET /_search
{
  "query": {
    "more_like_this": {
      "fields": [ "title", "description" ],
      "like": [
        {
          "_index": "imdb",
          "_id": "1"
        },
        {
          "_index": "imdb",
          "_id": "2"
        },
        "and potentially some more text here as well"
      ],
      "min_term_freq": 1,
      "max_query_terms": 12
    }
  }
}
```

Finally, users can mix some texts, a chosen set of documents but also provide documents not necessarily present in the index. To provide documents not present in the index, the syntax is similar to [artificial documents](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-termvectors).

```console
GET /_search
{
  "query": {
    "more_like_this": {
      "fields": [ "name.first", "name.last" ],
      "like": [
        {
          "_index": "marvel",
          "doc": {
            "name": {
              "first": "Ben",
              "last": "Grimm"
            },
            "_doc": "You got no idea what I'd... what I'd give to be invisible."
          }
        },
        {
          "_index": "marvel",
          "_id": "2"
        }
      ],
      "min_term_freq": 1,
      "max_query_terms": 12
    }
  }
}
```

## How it works [_how_it_works]

Suppose we wanted to find all documents similar to a given input document. Obviously, the input document itself should be its best match for that type of query. And the reason would be mostly, according to [Lucene scoring formula](https://lucene.apache.org/core/8_7_0/core/org/apache/lucene/search/similarities/TFIDFSimilarity.html), due to the terms with the highest tf-idf. Therefore, the terms of the input document that have the highest tf-idf are good representatives of that document, and could be used within a disjunctive query (or `OR`) to retrieve similar documents. The MLT query simply extracts the text from the input document, analyzes it, usually using the same analyzer at the field, then selects the top K terms with highest tf-idf to form a disjunctive query of these terms.

::::{important}
The fields on which to perform MLT must be indexed and of type `text` or `keyword`. Additionally, when using `like` with documents, either `_source` must be enabled or the fields must be `stored` or store `term_vector`. In order to speed up analysis, it could help to store term vectors at index time.
::::


For example, if we wish to perform MLT on the "title" and "tags.raw" fields, we can explicitly store their `term_vector` at index time. We can still perform MLT on the "description" and "tags" fields, as `_source` is enabled by default, but there will be no speed up on analysis for these fields.

```console
PUT /imdb
{
  "mappings": {
    "properties": {
      "title": {
        "type": "text",
        "term_vector": "yes"
      },
      "description": {
        "type": "text"
      },
      "tags": {
        "type": "text",
        "fields": {
          "raw": {
            "type": "text",
            "analyzer": "keyword",
            "term_vector": "yes"
          }
        }
      }
    }
  }
}
```


## Parameters [_parameters_2]

The only required parameter is `like`, all other parameters have sensible defaults. There are three types of parameters: one to specify the document input, the other one for term selection and for query formation.


### Document input parameters [_document_input_parameters]

`like`
:   The only **required** parameter of the MLT query is `like` and follows a versatile syntax, in which the user can specify free form text and/or a single or multiple documents (see examples above). The syntax to specify documents is similar to the one used by the [Multi GET API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-mget). When specifying documents, the text is fetched from `fields` unless overridden in each document request. The text is analyzed by the analyzer at the field, but could also be overridden. The syntax to override the analyzer at the field follows a similar syntax to the `per_field_analyzer` parameter of the [Term Vectors API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-termvectors). Additionally, to provide documents not necessarily present in the index, [artificial documents](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-termvectors) are also supported.

`unlike`
:   The `unlike` parameter is used in conjunction with `like` in order not to select terms found in a chosen set of documents. In other words, we could ask for documents `like: "Apple"`, but `unlike: "cake crumble tree"`. The syntax is the same as `like`.

`fields`
:   A list of fields to fetch and analyze the text from. Defaults to the `index.query.default_field` index setting, which has a default value of `*`. The `*` value matches all fields eligible for [term-level queries](/reference/query-languages/query-dsl/term-level-queries.md), excluding metadata fields.


### Term selection parameters [mlt-query-term-selection]

`max_query_terms`
:   The maximum number of query terms that will be selected. Increasing this value gives greater accuracy at the expense of query execution speed. Defaults to `25`.

`min_term_freq`
:   The minimum term frequency below which the terms will be ignored from the input document. Defaults to `2`.

`min_doc_freq`
:   The minimum document frequency below which the terms will be ignored from the input document. Defaults to `5`.

`max_doc_freq`
:   The maximum document frequency above which the terms will be ignored from the input document. This could be useful in order to ignore highly frequent words such as stop words. Defaults to unbounded (`Integer.MAX_VALUE`, which is `2^31 - 1` or 2147483647).

`min_word_length`
:   The minimum word length below which the terms will be ignored. Defaults to `0`.

`max_word_length`
:   The maximum word length above which the terms will be ignored. Defaults to unbounded (`0`).

`stop_words`
:   An array of stop words. Any word in this set is considered "uninteresting" and ignored. If the analyzer allows for stop words, you might want to tell MLT to explicitly ignore them, as for the purposes of document similarity it seems reasonable to assume that "a stop word is never interesting".

`analyzer`
:   The analyzer that is used to analyze the free form text. Defaults to the analyzer associated with the first field in `fields`.


### Query formation parameters [_query_formation_parameters]

`minimum_should_match`
:   After the disjunctive query has been formed, this parameter controls the number of terms that must match. The syntax is the same as the [minimum should match](/reference/query-languages/query-dsl/query-dsl-minimum-should-match.md). (Defaults to `"30%"`).

`fail_on_unsupported_field`
:   Controls whether the query should fail (throw an exception) if any of the specified fields are not of the supported types (`text` or `keyword`). Set this to `false` to ignore the field and continue processing. Defaults to `true`.

`boost_terms`
:   Each term in the formed query could be further boosted by their tf-idf score. This sets the boost factor to use when using this feature. Defaults to deactivated (`0`). Any other positive value activates terms boosting with the given boost factor.

`include`
:   Specifies whether the input documents should also be included in the search results returned. Defaults to `false`.

`boost`
:   Sets the boost value of the whole query. Defaults to `1.0`.


## Alternative [_alternative]

To take more control over the construction of a query for similar documents it is worth considering writing custom client code to assemble selected terms from an example document into a Boolean query with the desired settings. The logic in `more_like_this` that selects "interesting" words from a piece of text is also accessible via the [TermVectors API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-termvectors). For example, using the termvectors API it would be possible to present users with a selection of topical keywords found in a document’s text, allowing them to select words of interest to drill down on, rather than using the more "black-box" approach of matching used by `more_like_this`.


