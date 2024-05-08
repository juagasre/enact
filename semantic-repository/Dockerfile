FROM ghcr.io/graalvm/jdk:ol9-java17-22

COPY semantic-repo-assembly.jar /app/

MAINTAINER "Piotr Sowiński <piotr.sowinski@ibspan.waw.pl>"
WORKDIR /app
ENTRYPOINT ["java", "-jar", "semantic-repo-assembly.jar"]
