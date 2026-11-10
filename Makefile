GRADLE ?= ./gradlew
NPM ?= npm
# host, all, or a comma-separated list (plan §6.3). Passed to every Gradle call.
TARGETS ?= host
# cargo-zigbuild to call. macOS targets need 0.24.0 or newer (plan §6.2).
ZIGBUILD ?= cargo-zigbuild

GRADLE_ARGS := -Pkurpc.targets=$(TARGETS) -Pkurpc.zigbuild=$(ZIGBUILD)

.PHONY: native native_all aar test lint check update_npm update_npm_lock publish_local

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
	$(GRADLE) $(GRADLE_ARGS) :kurpc:jvmTest :protoc-gen-kurpc:test

lint:
	cargo fmt --check
	cargo clippy --workspace --all-targets -- -D warnings
	$(GRADLE) $(GRADLE_ARGS) :kurpc:lint :kurpc:checkKotlinAbi

# Everything CI's check job runs: Rust checks, jvmTest on JDK 21 and 8, Lint, ABI, the protoc
# plugin's tests.
check: lint
	cargo test --workspace
	$(GRADLE) $(GRADLE_ARGS) :kurpc:check :protoc-gen-kurpc:check

# Upgrade web-bridge dependencies to latest stable versions, keeping exact version pins.
update_npm:
	$(NPM) exec --yes --package=npm-check-updates -- npm-check-updates --packageFile web-bridge/package.json --upgrade
	$(MAKE) update_npm_lock

# Sync the bridge, Kotlin/JS and Kotlin/Wasm lockfiles with their dependency declarations.
update_npm_lock:
	$(NPM) --prefix web-bridge install --package-lock-only --ignore-scripts --no-audit --no-fund
	$(GRADLE) $(GRADLE_ARGS) kotlinUpgradePackageLock kotlinWasmUpgradePackageLock

publish_local:
	$(GRADLE) $(GRADLE_ARGS) publishToMavenLocal
