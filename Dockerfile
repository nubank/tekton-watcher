FROM clojure:openjdk-11-tools-deps AS builder

RUN mkdir /tmp/tekton-watcher

WORKDIR /tmp/tekton-watcher

COPY . .

RUN ./build/package.sh

FROM openjdk:12

RUN set -eu && \
  curl -LO \
  https://storage.googleapis.com/kubernetes-release/release/$(curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt)/bin/linux/amd64/kubectl && \
  chmod +x ./kubectl && \
  mv ./kubectl /usr/local/bin/kubectl

ENV TW_HOME=/opt/tekton-watcher

RUN mkdir $TW_HOME

COPY --from=builder /tmp/tekton-watcher/target/tekton-watcher.jar $TW_HOME

COPY build/tw.sh /usr/local/bin/tw

ENTRYPOINT ["tw"]
