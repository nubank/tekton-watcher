tag = 0.1.$(shell git rev-list --count master)
image_name = alangh/tekton-watcher
image = $(image_name):$(tag)

.PHONY: build

build:
	@docker build -t $(image) .

release: build
	@git tag $(tag) && git push origin $(tag)
	@docker push $(image)
	@echo "Done"
