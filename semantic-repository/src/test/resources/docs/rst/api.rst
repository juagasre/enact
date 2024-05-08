Downloading the content
***********************

Downloading the models is very straightforward. The most explicit way is
to specify the namespace, model, version, and the desired format:

``GET /w3c/sosa/1.0/content?format=text/turtle``

You can also omit the ``format`` parameter to obtain the content in the
default format:

``GET /w3c/sosa/1.0/content``

If you have set the ``latest`` tag for this model, you can use it
instead of the explicit version, to fetch the most recent version of the
model.

There is also a second, shorter style of URLs for downloading content,
with the ``/c`` prefix:

1. ``GET /c/w3c/sosa/1.0/text/turtle``
2. ``GET /c/w3c/sosa/latest/text/turtle``
3. ``GET /c/w3c/sosa/1.0``
4. ``GET /c/w3c/sosa/latest``
5. ``GET /c/w3c/sosa``
