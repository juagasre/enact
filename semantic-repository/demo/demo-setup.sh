#!/bin/bash

set -eux

BASE="$1/v1"

# Set up dummy namespaces, models, versions
DUMMY_BASES='ns-dummy ns-dummy-1/model ns-dummy-1/model-1/v'
for dBase in $DUMMY_BASES
do
  for i in {1..30}
  do
    curl --fail -X POST -H "Content-Type: application/json" \
      -d '{"metadata": {"dummy": "yes", "dummy_number": "'"$i"'"}}' \
      "$BASE/m/$dBase-$i"
  done
done

# Asteroid Sugar Mining Inc.
curl --fail -X POST -H "Content-Type: application/json" \
      -d '{"metadata": {"dummy": "no", "owner": "Asteroid Sugar Mining Inc."}}' \
      "$BASE/m/sugar"

# Models
curl --fail -X POST -H "Content-Type: application/json" \
      -d '{"metadata": {"about": "pilots"}, "latestVersion": "1.2.0"}' \
      "$BASE/m/sugar/pilot-schema"

curl --fail -X POST -H "Content-Type: application/json" \
      -d '{"metadata": {"about": "asteroids"}, "latestVersion": "0.7.3"}' \
      "$BASE/m/sugar/asteroid-schema"

curl --fail -X POST -H "Content-Type: application/json" \
      -d '{"metadata": {"about": ["pilots", "asteroids"], "usedBy": "Interplanetary Semantic Mediator (IPSM)"}, "latestVersion": "2.2.1"}' \
      "$BASE/m/sugar/core-ontology"

# Model versions
curl --fail -X POST -H "Content-Type: application/json" \
      -d '{"defaultFormat": "application/json"}' \
      "$BASE/m/sugar/pilot-schema/1.0.0"

curl --fail -X POST -H "Content-Type: application/json" \
      -d '{"defaultFormat": "application/yaml"}' \
      "$BASE/m/sugar/pilot-schema/1.2.0"

curl --fail -X POST -H "Content-Type: application/json" \
      -d '{"defaultFormat": "application/json"}' \
      "$BASE/m/sugar/asteroid-schema/0.7.3"

curl --fail -X POST -H "Content-Type: application/json" \
      -d '{"defaultFormat": "text/turtle"}' \
      "$BASE/m/sugar/core-ontology/2.2.1"

# Content
curl --fail -X POST -F 'content=@data/pilot-schema.json;type=application/json' \
      "$BASE/m/sugar/pilot-schema/1.0.0/content?format=application/json"

curl --fail -X POST -F 'content=@data/pilot-schema.json;type=application/json' \
      "$BASE/m/sugar/pilot-schema/1.2.0/content?format=application/json"

curl --fail -X POST -F 'content=@data/pilot-schema.yaml;type=application/yaml' \
      "$BASE/m/sugar/pilot-schema/1.2.0/content?format=application/yaml"

curl --fail -X POST -F 'content=@data/asteroid-schema.json;type=application/json' \
      "$BASE/m/sugar/asteroid-schema/0.7.3/content?format=application/json"

curl --fail -X POST -F 'content=@data/core.xml;type=application/rdf+xml' \
      "$BASE/m/sugar/core-ontology/2.2.1/content?format=application/rdf%2bxml"

curl --fail -X POST -F 'content=@data/core.json;type=application/ld+json' \
      "$BASE/m/sugar/core-ontology/2.2.1/content?format=application/ld%2bjson"

curl --fail -X POST -F 'content=@data/core.ttl;type=text/turtle' \
      "$BASE/m/sugar/core-ontology/2.2.1/content?format=text/turtle"

# Docs
curl --fail -X POST -F 'content=@data/docs.tgz' \
      "$BASE/m/sugar/pilot-schema/1.2.0/doc_gen?plugin=gfm"

curl --fail -X POST -F 'content=@data/docs.tgz' \
      "$BASE/m/sugar/core-ontology/2.2.1/doc_gen?plugin=markdown"

sleep 3

# Check if all is good
curl -o /dev/null --fail "$BASE/m/sugar/core-ontology/latest/content"
curl -o /dev/null --fail "$BASE/c/sugar/pilot-schema"
curl -o /dev/null --fail "$BASE/m/sugar/core-ontology/latest/doc/"

echo ''
echo ''
echo 'Demo database created. Happy sugar mining!'
