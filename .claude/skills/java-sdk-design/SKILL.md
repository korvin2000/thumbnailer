---
name: java-sdk-design
description: >-
  Design and review sync-first Java libraries and SDKs consumed from Java or Kotlin.
  Use for public programming APIs, facades, interfaces, provider SPIs, fluent builders,
  configuration layers, package/module layout, or simplifying an overengineered SDK.
  Specialized for LLM transport libraries: hide implementation coordination while
  preserving provider semantics, resource ownership, streaming, and failure contracts.
  Not a REST endpoint design skill or an agent-orchestration framework.
metadata:
  version: "1.0.0"
  profile: "sync-first"
  domain: "java-kotlin-llm-transport"
---

# Java SDK Design

Design from the caller inward. Make ordinary tasks direct, advanced control discoverable, and behavior predictable. Absorb implementation complexity without hiding consequential policy. Prefer a small, cohesive public surface over either a universal framework or a collection of wrappers that callers must assemble.

**Sync-first is the default contract:** ordinary calls return ordinary values or throw documented exceptions; streaming has an explicitly closeable blocking interface. Async, reactive, and Kotlin coroutine integrations are optional, justified additions—not prerequisites for basic use.

## Scope and reference routing

Use the project's requirements, supported Java/Kotlin versions, existing contracts, and dependency constraints as the source of truth. This skill shapes the design; it does not require implementing every capability it mentions. Preserve a sound existing design and make proportionate changes.

Read only references relevant to the decision; their recommendations are self-contained:

| Decision | Read |
|---|---|
| New or changed public methods, models, fluent configuration, Java/Kotlin ergonomics | [API and configuration](references/api-and-configuration.md) |
| Package layout, facade responsibilities, API/SPI boundaries, extension mechanisms | [Structure and extension](references/structure-and-extension.md) |
| Network execution, streaming, ownership, timeouts, cancellation, hooks, optional async | [Execution and lifecycle](references/execution-and-lifecycle.md) |
| Provider abstraction, capabilities, metadata, LLM content, tools, protocol fidelity | [LLM transport profile](references/llm-transport.md) |

For provenance and deliberately rejected upstream rules, see [SOURCES.md](SOURCES.md). For evaluating this skill itself—not the SDK—see [evaluation instructions](evals/README.md) and [scenarios](evals/scenarios.json). Do not load these during routine design.

## Workflow

### 1. Establish the actual contract

Identify the caller, task, target runtime, compatibility obligations, and what already exists. Distinguish application developers using the API from provider authors implementing the SPI. Inspect relevant public types, usage, build descriptors, and tests; do not invent an existing codebase.

For greenfield work, state only consequential assumptions. For a mature library, retain existing naming and behavior unless a concrete defect justifies change. Finish when the requested scope and the caller-visible constraints are explicit.

### 2. Write the consumer experience first

Write realistic Java usage for setup, one complete operation, and cleanup. Include advanced configuration, an important error, and Kotlin usage when relevant. Show a streaming lifetime or extension example when that is in scope. Label proposed calls as proposals until those types actually exist.

Judge discovery, decisions, coordination, and cleanup—not just line count. The common caller should not compose codecs, dispatchers, transports, and retry machinery. The advanced caller should not need reflection or casts to private implementation classes.

For a consequential new surface, compare at least two meaningfully different designs with the same caller task. For a small local change, compare the proposed change with keeping the current design; do not manufacture a redesign exercise. Finish with a chosen usage shape and its material tradeoff.

### 3. Assign responsibility and ownership

Map each public concept to one responsibility and each resource to one owner. Separate the consumer API, intentional provider SPI, and implementation. A facade may expose focused sub-APIs; it must not become the implementation of every feature.

Group code by cohesive capability and shared invariants. Introduce an interface, indirection, package, or build module only when it buys a real boundary, substitution, independent dependency, or release benefit. Finish with a compact package map and clear dependency direction—not empty scaffolding.

### 4. Specify behavior before implementation

Define construction, defaults, per-call overrides, mutability, null/absence semantics, error categories, thread safety, and resource lifetime for the affected surface. For execution, include deadline, interruption, partial-result, retry, and cancellation behavior. For an SPI, define what implementers must guarantee and what the core owns.

Design critical Javadocs alongside signatures. An API is unfinished when callers must read the implementation to learn whether it sends a request, starts work, owns a resource, or can be retried. Finish when the examples and contracts agree.

### 5. Prove the boundary; remove unjustified machinery

Compile representative Java and Kotlin caller code against public exports. Test relevant behavior through the facade and test provider implementations against the same SPI contract. Keep useful focused internal tests; replace only genuinely redundant or implementation-coupled tests.

For a changed published API, check source, binary, and behavioral compatibility using available tooling and downstream fixtures. Inspect dependencies and exported types. Delete or simplify constructs whose removal makes neither callers nor maintainers handle more real complexity.

Report what was designed, compiled, executed, or left unverified separately. A compiling example is evidence of type usability, not proof of intuitive design, transport correctness, or measured skill effectiveness.

## Design rules

### Public API and facade

- Use domain vocabulary consistently across operations, configuration, errors, and documentation. Prefer discoverable names to abbreviated DSL vocabulary.
- Expose task-level operations with useful results. A text convenience method may coexist with a structured result; it must not replace access to tool calls, usage, completion status, or continuation data.
- Prefer a direct operation for a small cohesive surface. Add a sub-API when it groups a genuine capability or lifetime, not merely to add dots to a call chain.
- Keep lower-level supported control separate from private internals. Any escape hatch is still an API contract with validation, ownership, and compatibility obligations.
- Retain meaningful failure distinctions and provider limitations. Convenience must not silently select another provider, discard an option, execute tools, or imply guarantees the protocol lacks.

### OOP and types

- Put invariants and behavior with the object that owns them. Use composition for collaborators; use inheritance only where semantic substitutability and an intentional extension contract justify it.
- Keep implementation types non-public where possible. A final concrete facade is acceptable; interfaces are valuable at actual consumer/provider boundaries, not as mandatory twins of every class.
- Use immutable configuration, requests, results, and events where practical; make defensive copies of mutable collections and payloads. Builders remain mutable and normally not thread-safe.
- Use semantic types where confusion causes real errors. Avoid both a public `Map<String, Object>` model and a new wrapper type for every harmless scalar.
- Choose records for appropriately stable value shapes, not merely brevity. Choose open versus sealed hierarchies with external extension and future protocol variants in mind.
- Keep generics readable. Use variance at real producer/consumer boundaries; avoid recursive generic base classes or type-state machinery unless a concrete invariant earns the complexity.

### Configuration

- Give the common path sensible, documented defaults and minimal required input. Use a constructor or named factory for a small complete value; use a fluent builder for independently optional choices.
- Use a few meaningful configuration groups. Offer nested `Consumer<Builder>` configuration where it reduces ceremony; keep a canonical completed-value path for reuse and Java/Kotlin interoperation.
- End configuration in an immutable snapshot. Later builder edits must not mutate built objects. Keep network discovery, credential refresh, and model loading out of ordinary `build()`.
- Define precedence and merge semantics once. Separate client-wide operational policy, generation defaults, and per-call overrides; omitted values must not overwrite inherited values.
- Validate local invariants early and cross-field combinations at construction. Report dynamic provider restrictions at the appropriate later boundary. Reject invalid values rather than silently clamping them.
- Make environment lookup and credential sourcing explicit, bounded, and documented. Never let mutable global configuration cause independent clients or tenants to affect each other.

### Hierarchy and extension

- Prefer the smallest viable artifact set and a cohesive package hierarchy. Split artifacts for optional dependencies, deployment, or compatibility boundaries—not one artifact per abstraction.
- Keep the stable core independent of concrete provider implementations and optional application frameworks. A small composition entry point can supply a default implementation without exposing its wiring.
- Distinguish a substitutable strategy/adapter from an observer and from an interceptor that changes execution. Provide only the extension mechanisms required by actual use cases.
- Prefer explicit instance-level registration or injection. Use `ServiceLoader` only when discovery is a requirement and its selection, class-loader, and lifecycle behavior are defined.
- Accept legitimate thin adapters, compatibility wrappers, and fluent conveniences when they remove caller work. Reject redundant layers, not delegation itself.

### Sync-first execution

- Keep the blocking path first-class and usable without Reactor, coroutines, an event loop, a DI container, or executor configuration. Share semantics with optional integrations rather than requiring an async engine by default.
- Do not equate sync-first with single-threaded. Design reusable clients for concurrent independent calls; keep request state and stream state isolated.
- Make ownership, cancellation, and cleanup explicit. SDK-created resources are SDK-owned; injected resources are borrowed unless ownership transfer is explicit.
- Preserve interruption and propagate cancellation to transport work where supported. Do not claim that cancelling a future alone stops I/O or remote generation.
- Expose incremental blocking streaming without mandatory full accumulation. Define one-shot consumption, bounded buffering, early-close behavior, and terminal outcomes.
- Retry only with justified operation semantics, replayable input, bounded attempts, and a total deadline. Never silently restart a stream after caller-visible output.

### LLM transport fidelity

- Separate endpoint/authentication, protocol adapter, provider identity, and model identity without turning each into a mandatory public manager.
- Normalize only compatible semantics. Preserve typed provider-specific options and opaque continuation material where a common model cannot faithfully represent them.
- Represent unknown capabilities, limits, prices, and usage as unknown—not false, zero, or fabricated defaults. Record provenance/freshness where metadata informs decisions.
- Transport tool definitions, calls, and results; leave tool execution, memory, planning, RAG, and agent loops to consumers unless explicitly requested as a separate layer.

## Completion check

Before presenting the result, confirm the affected scope satisfies these checks:

- [ ] Common Java and Kotlin usage is direct; proposed versus implemented APIs are distinguished.
- [ ] Every public abstraction and package split has a caller, invariant, or dependency justification.
- [ ] Configuration precedence, validation, mutability, and secrets handling are explicit.
- [ ] API, SPI, internals, ownership, and concurrency responsibilities do not conflict.
- [ ] Sync and streaming paths define errors, termination, cleanup, and partial outcomes.
- [ ] Provider-specific meaning and unsupported/unknown cases survive the facade.
- [ ] Relevant verification is reported honestly; compatibility implications are identified.

Present the proposed usage or the most important finding first. Follow with the package/dependency shape, consequential contracts, and verification evidence as needed. Keep the output proportional to the task; do not impose a fixed architecture document on a local change.
