# SimpleAttributeSystem - Project Memory

## Project Overview
- **Name**: SimpleAttributeSystem (Ad Attribution System)
- **Purpose**: Real-time multi-touch attribution for click/conversion events
- **Stack**: Java 11, Flink 1.17.1, Kafka 3.4.0, Fluss 0.8.0-incubating
- **Repo**: https://github.com/foridealcheng/simple-attribute-system
- **Path**: `/Users/ideal/.openclaw/workspace/SimpleAttributeSystem`

## Release History

### v1.0.0 (Released) ✅
- **Branch**: `release/v1.0.0`, `main`
- **Commit**: 7577809
- **Features**: 4 attribution models (Last Click, Linear, Time Decay, Position Based)
- **State**: Flink MapState (no Fluss)
- **Tests**: 12/12 unit tests passing
- **Status**: Waiting for Code Review approval

### v2.0.0 (In Progress) 🚧
- **Branch**: `feature/v2.0.0`
- **Version**: 2.0.0-SNAPSHOT
- **Key Change**: Fluss KV Store for state externalization
- **Latest Commit**: 757de39

## Key Technical Decisions

### Fluss Integration Strategy
- **v1.0.0**: No Fluss (Flink MapState only)
- **v2.0.0**: Fluss-priority approach
- **Fluss Version**: 0.8.0-incubating (latest stable as of Feb 2026)
- **Flink Connector**: Not yet published → using direct KV client
- **Approach**: FlussKVClient wrapper for direct KV access

### Architecture
- **Source**: Kafka (click-events, conversion-events)
- **Processing**: Flink with custom AttributionProcessFunction
- **State**: MapState (v1.0.0) → Fluss KV Store (v2.0.0)
- **Sink**: Kafka (attribution-results-success, attribution-results-failed)

## Local Environment
- **Flink UI**: http://localhost:8081
- **Kafka UI**: http://localhost:8090
- **Kafka Broker**: localhost:9092
- **Docker**: docker-compose.yml (Kafka, Flink, Zookeeper, Kafka UI)

## Attribution Models
1. **LastClickAttribution** - 100% credit to last click
2. **LinearAttribution** - Equal credit across all touches
3. **TimeDecayAttribution** - Exponential decay (half-life configurable)
4. **PositionBasedAttribution** - 40% first, 40% last, 20% middle

## Important Files
- **Entry Point**: `com.attribution.AttributionJob`
- **Core Logic**: `AttributionProcessFunction.java`
- **Models**: `com.attribution.function.impl.*`
- **Config**: `pom.xml`, `config/flink-conf.yaml`
- **Docs**: `/docs/` (ARCHITECTURE.md, PLAN-v2.0.0.md, etc.)

## Pending Work
- [ ] Task 2.1: Design Fluss KV Schema
- [ ] Task 2.2: Implement FlussKVClient
- [ ] Task 2.3: Refactor AttributionProcessFunction (MapState → Fluss)
- [ ] Kafka Sink implementation
- [ ] RocketMQ Retry implementation
- [ ] Wait for v1.0.0 Code Review approval
