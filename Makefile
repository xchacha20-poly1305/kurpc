GRADLE ?= ./gradlew
# host, all, or a comma-separated list (plan §6.3). Passed to every Gradle call.
TARGETS ?= host
# cargo-zigbuild to call. macOS targets need a build newer than 0.23.4 (plan §6.2).
ZIGBUILD ?= cargo-zigbuild

GRADLE_ARGS := -Pkurpc.targets=$(TARGETS) -Pkurpc.zigbuild=$(ZIGBUILD)

.PHONY: native native_all aar test lint check publish_local

# Release natives jars for the selected desktop targets.
native:
	$(GRADLE) $(GRADLE_ARGS) :kurpc:nativesJars

# Every desktop natives jar and the AAR with all four ABIs.
native_all:
	$(GRADLE) -Pkurpc.targets=all -Pkurpc.zigbuild=$(ZIGBUILD) :kurpc:nativesJars :kurpc:assemble

aar:
	$(GRADLE) $(GRADLE_ARGS) :kurpc:assemble

test:
	cargo test --workspace
	$(GRADLE) $(GRADLE_ARGS) :kurpc:jvmTest

lint:
	cargo fmt --check
	cargo clippy --workspace --all-targets -- -D warnings
	$(GRADLE) $(GRADLE_ARGS) :kurpc:lint :kurpc:checkKotlinAbi

# Everything CI's check job runs: Rust checks, jvmTest on JDK 21 and 8, Lint, ABI.
check: lint
	cargo test --workspace
	$(GRADLE) $(GRADLE_ARGS) :kurpc:check

publish_local:
	$(GRADLE) $(GRADLE_ARGS) publishToMavenLocal
