// Day 1 spike: MongoDB hybrid search on three toy documents (3-dimension vectors, no embedding API).
// Run from the repo root:
//   mongosh "mongodb://localhost:27017/?directConnection=true" --file spike/spike.js
// Safe to re-run: it drops and recreates spike.chunks every time.
//
// Steps follow "Day 1 spike runbook" in docs/PLAN.md.

var NORMALIZATION = "sigmoid";                  // for $scoreFusion; also try "minMaxScaler"
var QUERY_TEXT = "refund days";
var QUERY_VECTOR = [0.85, 0.15, 0.0];          // close to the refund chunk's embedding
var EXPECTED_FIRST = "d1";                      // the refund chunk

var spikeDb = db.getSiblingDB("spike");

function banner(title) {
  print("\n==================== " + title + " ====================");
}

function sleepMs(ms) { sleep(ms); }

// --- 0. Server version -------------------------------------------------------------------------
banner("0. db.version()");
print(db.version());
print("($scoreFusion needs MongoDB 8.2+, some doc versions say 8.3+. If older, try the :preview image tag.)");

// --- 1. Toy data -------------------------------------------------------------------------------
banner("1. Recreate spike.chunks and insert 3 toy documents");
spikeDb.chunks.drop();
spikeDb.chunks.insertMany([
  { tenantId: "demo", docId: "d1", page: 1, text: "Refund requests are processed within 14 days.",   embedding: [0.9, 0.1, 0.0] },
  { tenantId: "demo", docId: "d2", page: 3, text: "Invoices are emailed on the first business day.", embedding: [0.1, 0.9, 0.0] },
  { tenantId: "demo", docId: "d3", page: 2, text: "Error code E-401 means the token has expired.",   embedding: [0.0, 0.2, 0.9] }
]);
print("inserted: " + spikeDb.chunks.countDocuments({}));

// --- 2. Search indexes -------------------------------------------------------------------------
banner("2. Create search indexes chunk_vector and chunk_text");

var INDEXES = [
  { name: "chunk_vector", type: "vectorSearch", definition: {
      fields: [
        { type: "vector", path: "embedding", numDimensions: 3, similarity: "cosine" },
        { type: "filter", path: "tenantId" }
      ] } },
  { name: "chunk_text", type: "search", definition: {
      mappings: { dynamic: false, fields: {
        text:     { type: "string", analyzer: "lucene.english" },
        tenantId: { type: "token" }
      } } } }
];

// After a drop, mongot may still be removing the old index of the same name, so retry on errors.
INDEXES.forEach(function (ix) {
  var created = false;
  for (var attempt = 1; attempt <= 20 && !created; attempt++) {
    try {
      if (ix.type === "vectorSearch") {
        spikeDb.chunks.createSearchIndex(ix.name, "vectorSearch", ix.definition);
      } else {
        spikeDb.chunks.createSearchIndex(ix.name, ix.definition);
      }
      created = true;
      print("created index " + ix.name + " (" + ix.type + ")");
    } catch (e) {
      print("  attempt " + attempt + " for " + ix.name + ": " + e.message);
      sleepMs(3000);
    }
  }
  if (!created) { print("GIVING UP on index " + ix.name); quit(1); }
});

banner("3. Wait until both indexes are queryable (max ~3 minutes)");
var ready = false;
for (var i = 1; i <= 90 && !ready; i++) {
  var idx = spikeDb.chunks.getSearchIndexes();
  print("poll " + i + ": " + idx.map(function (x) {
    return x.name + " status=" + x.status + " queryable=" + x.queryable;
  }).join(" | "));
  ready = idx.length === 2 && idx.every(function (x) { return x.queryable === true; });
  if (!ready) { sleepMs(2000); }
}
if (!ready) { print("Indexes did not become queryable in time. Check: docker ps (healthy?)"); quit(1); }

// queryable can flip before the 3 documents are searchable, so warm up until both indexes see them.
print("warming up (waiting until both indexes return all 3 documents)...");
var warm = false;
for (var w = 1; w <= 30 && !warm; w++) {
  try {
    var nVec = spikeDb.chunks.aggregate([
      { $vectorSearch: { index: "chunk_vector", path: "embedding", queryVector: QUERY_VECTOR,
                         numCandidates: 20, limit: 3, filter: { tenantId: "demo" } } }
    ]).toArray().length;
    var nTxt = spikeDb.chunks.aggregate([
      { $search: { index: "chunk_text", exists: { path: "text" } } }
    ]).toArray().length;
    print("  warm-up " + w + ": vector=" + nVec + " text=" + nTxt);
    warm = (nVec === 3 && nTxt === 3);
  } catch (e) {
    print("  warm-up " + w + ": " + e.message);
  }
  if (!warm) { sleepMs(2000); }
}

// --- 3. Queries --------------------------------------------------------------------------------
var PIPELINES = {
  vector: [ { $vectorSearch: { index: "chunk_vector", path: "embedding",
              queryVector: QUERY_VECTOR, numCandidates: 20, limit: 3,
              filter: { tenantId: "demo" } } } ],
  text:   [ { $search: { index: "chunk_text", text: { query: QUERY_TEXT, path: "text" } } },
            { $limit: 3 } ]
};

var results = {};   // label -> docId of the first hit, or null

function run(label, pipeline, showDetails) {
  banner(label);
  try {
    var t0 = Date.now();
    var out = spikeDb.chunks.aggregate(pipeline).toArray();
    print(out.length + " docs in " + (Date.now() - t0) + " ms");
    out.forEach(function (d, n) {
      print("  #" + (n + 1) + "  score=" + d.score + "  [" + d.docId + ", page " + d.page + "]  " + d.text);
      if (showDetails && d.details) { print("       details: " + JSON.stringify(d.details)); }
    });
    results[label] = out.length > 0 ? out[0].docId : null;
  } catch (e) {
    print("FAILED: " + e.message);
    print("  -> if a fusion stage fails but vector and text work, the plan falls back to Java RRF.");
    results[label] = "FAILED";
  }
}

var FUSION_PROJECT = { $project: { text: 1, docId: 1, page: 1,
                                    score: { $meta: "score" }, details: { $meta: "scoreDetails" } } };

run("4a. $vectorSearch alone (query vector " + JSON.stringify(QUERY_VECTOR) + ")",
    PIPELINES.vector.concat([ { $project: { text: 1, docId: 1, page: 1, score: { $meta: "vectorSearchScore" } } } ]),
    false);

run("4b. $search alone (BM25-style text search, query \"" + QUERY_TEXT + "\")",
    PIPELINES.text.concat([ { $project: { text: 1, docId: 1, page: 1, score: { $meta: "searchScore" } } } ]),
    false);

run("4c. $rankFusion (reciprocal rank fusion, weights vector=1 text=1)",
    [ { $rankFusion: { input: { pipelines: PIPELINES },
                       combination: { weights: { vector: 1, text: 1 } }, scoreDetails: true } },
      FUSION_PROJECT ],
    true);

run("4d. $scoreFusion (normalization=" + NORMALIZATION + ", weights vector=1 text=1)",
    [ { $scoreFusion: { input: { pipelines: PIPELINES, normalization: NORMALIZATION },
                        combination: { weights: { vector: 1, text: 1 } }, scoreDetails: true } },
      FUSION_PROJECT ],
    true);

// --- 4. Summary --------------------------------------------------------------------------------
banner("Summary: first hit for \"" + QUERY_TEXT + "\" should be " + EXPECTED_FIRST + " (refund chunk)");
Object.keys(results).forEach(function (label) {
  var first = results[label];
  var verdict = first === EXPECTED_FIRST ? "OK    " : (first === "FAILED" ? "FAILED" : "CHECK ");
  print(verdict + " first=" + first + "   " + label.substring(0, 60));
});
print("\nDone. Paste the output of sections 0, 3 (last poll) and 4a-4d back to Claude Code.");
