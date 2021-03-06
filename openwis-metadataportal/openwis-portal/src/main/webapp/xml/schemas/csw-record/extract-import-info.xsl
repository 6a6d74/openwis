<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" 
	xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
	xmlns:dc="http://purl.org/dc/elements/1.1/"    
	xmlns:ows="http://www.opengis.net/ows"
	xmlns:csw="http://www.opengis.net/cat/csw/2.0.2"
	xmlns:dct="http://purl.org/dc/terms/">

	<xsl:template match="csw:Record">
		<importInfo>
			<uuid>
				<xsl:value-of select="dc:identifier"/>
			</uuid>
			<dateStamp>
				<xsl:value-of select="dct:modified"/>
			</dateStamp>
		</importInfo>
	</xsl:template>

</xsl:stylesheet>
