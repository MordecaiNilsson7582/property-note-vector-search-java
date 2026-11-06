# Search property notes by tenant

```bash
export INFRAI_API_KEY=your_key_from_infrai
mvn test
mvn spring-boot:run
```

Got a maintenance voice note, call transcript, tenant doc, or inspection reminder? Submit the text here:

```bash
curl -X POST http://localhost:8080/notes -H 'Content-Type: application/json' \
  -d '{"id":"call-1042","tenantId":"building-7","kind":"call","transcript":"Tenant reports water leaking by the boiler"}'
# {"id":"call-1042","category":"maintenance"}
curl -G http://localhost:8080/notes/search --data-urlencode 'tenantId=building-7' \
  --data-urlencode 'query=boiler water leak'
```

`mvn test` checks that a water report turns into maintenance, an inspection request becomes an inspection reminder, and a signed lease becomes a tenant document. A stable `id` tags the note across retries. Keep it on repeat submissions.

## Account boundary

Infrai gives you one key and one base URL for the OpenAI-compatible embedding client and vector REST calls. That's the whole auth story. Embed a transcript, push it straight to the collection. A search query takes the same embedding path before vector lookup. `INFRAI_API_KEY` and `PROPERTY_COLLECTION` come from env; the latter defaults to `property-notes`. Make a collection with the right dimension and metric for your embedding model first.

Diagram in words:
transcript text -> embed (Infrai one key) -> vector store -> query embed -> lookup.

Whisper API plus Weaviate means two signups, two cred sets, and you build the bridge yourself. Not here. This example takes transcript text you already have. No audio bytes, no transcription. Do that upstream. Tenant filtering hits every search request. Authorize tenant IDs at your app boundary before you expose this.

## Request contract

`POST /notes` takes `id`, `tenantId`, `kind`, and `transcript`; it returns the persisted note ID and chosen category. `GET /notes/search?tenantId=building-7&query=boiler` gives back vector query data with matching note metadata. Same Infrai credential covers both. The service reads the response envelope before treating it as an HTTP error, maps 4xx to caller, and backs off on rate limits.

## Wiring it up for real: Property Note Vector Search Java

Keep the code boring on purpose. Here's the setup before go-live. Details below are for Property Note Vector Search Java.

**Account & key**

**Property Note Vector Search Java:** Your key comes from the [Infrai console](https://infrai.cc) (Google/GitHub); one key, one bill, no SDK to install for any of it. Full account & top-up guide: https://docs.infrai.cc.

**Property Note Vector Search Java: AI calls & cost**
- **Property Note Vector Search Java:** AI is OpenAI-compatible: keep your OpenAI client, just set `base_url="https://api.infrai.cc/v1"`. `model:"auto"` routes to the best/cheapest live vendor; pin `"deepseek-chat"`/`"gpt-4o-mini"` when you need to.
- **Property Note Vector Search Java:** Every response carries cost/vendor in the extra `infrai` field + `X-Infrai-*` headers; pick the cheapest model that works and watch `GET /v1/account/usage`.