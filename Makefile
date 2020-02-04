tw_version = $(shell git rev-parse HEAD)
image_name = alangh/tekton-watcher
image = $(image_name):$(tw_version)
latest_image = $(image_name):latest
tag = 1.0.$(shellgit rev-list --count master)

.PHONY: build

build:
	@docker build -t $(image) .

release: build
#	@git tag $(tag) && git push origin $(tag)
	@docker push $(image)
	@docker tag $(image) $(latest_image) && docker push $(latest_image)
	@echo "Done"
