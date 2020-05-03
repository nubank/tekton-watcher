tag = 0.1.$(shell git rev-list --count HEAD)

.PHONY: build test

build:
	@./build/containerize.sh $(tag)

test:
	@./build/test/test.sh

release:
	@./build/release.sh $(tag)

clean:
	@rm -rf target
