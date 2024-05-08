# Demo database script

This directory contains a script that sets up the Semantic Repository with an example database that you can use for testing and exploring its features.

## How to run this?

The script was only tested on Linux. To run it, you need bash and curl (the command-line program). To check if you have them installed, run:
```shell
bash --version
curl --version
```

Before you run the script, you will have to set up the Semantic Repository first. Please refer to the installation manual for how to do that. To run the script itself, you will have to only tell it where to look for the Semantic Repository. For example, if you want to setup the demo database on a SemRepo instance running on localhost, port 8080, use:
```shell
./demo-setup.sh "http://localhost:8080"
```

Notice the lack of a trailing slash.

## Demo database contents

- Dummy namespaces, models, versions – these can be useful for testing paging and sorting, for example. There are 30 of each, named:
  - Namespaces: `ns-dummy-{1..30}`
  - Models: `ns-dummy-1/model-{1..30}`
  - Versions: `ns-dummy-1/model-1/v-{1..30}`
- A namespace of an interplanetary corporation (*Asteroid Sugar Mining Inc.*), who need to manage a lot of data on their asteroids and pilots. Their line of operation is hunting down sugar asteroids and mining them. Yes, sugar is mined like salt. Didn't you know that? Anyway, they have a namespace called `sugar`. Models:
  - `pilot-schema` – JSON Schema with two versions. The latest version has two formats and some attached documentation.
  - `asteroid-schema` – JSON schema with one version and one format.
  - `core-ontology` – RDF/OWL ontology with one version and three formats. This one also has some attached documentation.
