###########################
Semantic Repository enabler
###########################

.. contents::
  :local:
  :depth: 1


***************
Introduction
***************

This enabler offers a “nexus” for data models, ontologies, and other
files, that can be uploaded in different file formats, and served to
users with relevant documentation. This enabler is aimed to support
files that describe data models or support data transformations, such as
ontologies, schema files, semantic alignment files etc. However, there
are no restrictions on file format and size.

Overall focus of the Semantic Repository’s design is high performance,
scalability, and resiliency. It should be able to scale up and down to
meet the specific use case.

============= ============
Request URL   Request body
============= ============
``POST /w3c`` (empty)
============= ============

.. figure:: image.png
   :alt: Enabler architecture