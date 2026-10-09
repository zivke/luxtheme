# Lux Theme build helpers.
#
# In the devcontainer the Android SDK (ANDROID_HOME) and adb are already set
# up; the signing key is named in .env. See .devcontainer/README.md. Outside it, set
# ANDROID_HOME or TOOLS yourself as README.md describes.
# Override any variable on the command line, e.g. `make run DEVICE=192.168.1.50:5555`.

PACKAGE   ?= app.luxtheme
ACTIVITY  ?= .MainActivity
APK       ?= luxtheme.apk
ADB_PORT  ?= 5555
# Address for `make connect`. Set LUXTHEME_DEVICE_IP on the host to preset it.
DEVICE_IP ?=
# adb serial, only needed when more than one device is attached.
DEVICE    ?=

ADB       := adb $(if $(DEVICE),-s $(DEVICE))
TEST_OUT  := build/test

.DEFAULT_GOAL := build
.PHONY: build test connect install run stop uninstall logcat clean help

## build: compile, package and sign luxtheme.apk
build:
	./build.sh

## test: run the debounce-logic test on the local JVM (no device needed)
test:
	@mkdir -p $(TEST_OUT)
	javac -nowarn -d $(TEST_OUT) src/app/luxtheme/Debouncer.java test/app/luxtheme/DebouncerTest.java
	java -ea -cp $(TEST_OUT) app.luxtheme.DebouncerTest

## connect: attach to the device over the network (make connect DEVICE_IP=192.168.1.50)
connect:
	@if [ -z "$(DEVICE_IP)" ]; then \
		echo ">> usage: make connect DEVICE_IP=<address of the device>"; exit 1; \
	fi
	adb connect $(DEVICE_IP):$(ADB_PORT)
	adb devices

## install: build, then install the APK on the device, keeping its settings
install: build
	$(ADB) install -r $(APK)

## run: install, then open the app
run: install
	$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)

## stop: force-stop the app and its monitor service
stop:
	$(ADB) shell am force-stop $(PACKAGE)

## uninstall: remove the app from the device
uninstall:
	$(ADB) uninstall $(PACKAGE)

## logcat: follow the app's log output (the app must be running)
logcat:
	@pid=$$($(ADB) shell pidof -s $(PACKAGE) | tr -d '\r'); \
	if [ -z "$$pid" ]; then echo ">> $(PACKAGE) is not running"; exit 1; fi; \
	$(ADB) logcat --pid=$$pid

## clean: remove build output
clean:
	rm -rf build $(APK)

## help: list targets
help:
	@grep -hE '^## ' $(MAKEFILE_LIST) | sed 's/^## /  /'
