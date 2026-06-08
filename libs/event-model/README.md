# event-model — canonical event library

The single source of truth for the platform's event **envelope**, the nine
**payloads**, and the `managedObjectId` identity scheme. One **JSON Schema
2020-12** source under [`schema/`](schema/) drives two language bindings:

- **Python / Pydantic v2** — [`python/`](python/), package `acp-event-model`.
- **Java / Jackson** — [`java/`](java/) (added by the Java binding build).

It is a pure contract/binding library: **no business logic, no Kafka, no HTTP,
no runtime process**. Every service imports it; no service depends on another
service's source to access event shapes.

See [`spec.md`](spec.md) (the frozen contract, 18 acceptance criteria) and
[`design.md`](design.md) (the build blueprint).

## Layout

```
schema/                       SINGLE SOURCE OF TRUTH (JSON Schema 2020-12)
  envelope.schema.json        the 7-field envelope
  common/managedObjectId.schema.json   <objectType>:<id> identity scheme
  payloads/*.schema.json      the 9 payload schemas
  fixtures/*.json             9 golden envelopes (one per payload type) —
                              owned by the schema, read by BOTH bindings' tests
python/                       acp-event-model (Pydantic v2 binding)
  src/acp_event_model/        generated models + codec/version/managedObjectId/registry
  scripts/generate.py         regenerate the models from schema/ (single-source)
  tests/                      pytest suite (reads ../schema/fixtures)
java/                         com.acp:event-model (Jackson binding)
```

## The single-source guarantee (criterion 2)

The model classes are **never hand-authored**. Both bindings are generated from
the same `schema/*.json` files:

- **Python:** `python/scripts/generate.py` bundles the schema files into one
  JSON Schema document and runs `datamodel-code-generator` to produce
  `python/src/acp_event_model/_generated.py`.
- **Java:** the `jsonschema2pojo` Gradle task generates POJOs at build time.

A field change in the schema propagates to both bindings on the next
build/generate with **zero manual edits**. The only hand-written code in either
binding is the thin, schema-agnostic helper layer (codec, version policy,
`managedObjectId` validator, `type` discriminator registry).

## Canonical wire format

Both bindings agree on this exact JSON encoding (see `design.md` for the full
table): nested `payload` object; exact camelCase field names; integer
`schemaVersion`; ISO-8601 **UTC with a `Z` suffix** for all datetimes; lowercase
string enums (`state` ∈ `raised`/`cleared`); optional fields **omitted** when
absent (accepted as omitted-or-null on input); empty arrays emitted as `[]`;
`managedObjectId` as a single string; unknown fields rejected on strict
deserialize.

## Python binding — build, test, use

### Regenerate models from the schema

```bash
cd libs/event-model/python
python -m pip install -e '.[dev]'
python scripts/generate.py        # rewrites src/acp_event_model/_generated.py
```

### Install + test

```bash
cd libs/event-model/python
python -m pip install -e '.[dev]'
ruff check . && black --check . && pytest --cov --cov-fail-under=80
```

Or a clean install from the repo root:

```bash
pip install ./libs/event-model/python
python -c "import acp_event_model"
```

### Use (downstream Python service)

```python
from acp_event_model import (
    deserialize, serialize, TypedEnvelope,
    AlarmEvent, ManagedObjectId,
    CodecError, SchemaVersionError, UnknownEventTypeError,
)

env = deserialize(kafka_message_bytes)   # raises on bad version / unknown type / bad payload
if isinstance(env.payload, AlarmEvent):
    moi = ManagedObjectId.parse(env.payload.managedObjectId)  # objectType + id
wire_json = serialize(env)               # canonical wire JSON
```

Deserialization errors are the signal a consuming service uses to route a poison
message to `<topic>.dlq` (the library itself has no Kafka/DLQ behaviour).

## Extensibility (no-fork rule)

A service that needs a service-local convenience view may **subclass** a payload
model in its own codebase without editing this library:

```python
from acp_event_model import AlarmEvent

class EnrichedAlarmView(AlarmEvent):
    ...  # service-local helpers only — no new wire fields
```

The contract surface (the nine payloads + envelope) is **closed for
modification here but open for extension downstream**. Adding or removing a
*wire* field is a **contract change** to the schema (requires a
`docs/architecture.md` update and human approval), never a subclass.

## Versioning & compatibility

`schemaVersion` is an integer major version. The initial supported major is
**1**: consumers accept `1` and **reject ≥ 2** (`SchemaVersionError` /
exception). Minor versions are additive/backward-compatible.

## Licenses

All dependencies are permissive (Apache-2.0 / MIT / BSD): Pydantic,
datamodel-code-generator, jsonschema2pojo, Jackson, networknt
json-schema-validator. No GPL/AGPL/BSL.
