tag = 0.1.$(shell git rev-list --count HEAD)

.PHONY: build test

build:
	./build/containerize.sh $(tag)

test:
	@./build/test.sh

clean:
	@rm -rf target
