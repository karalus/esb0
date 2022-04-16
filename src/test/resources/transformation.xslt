<xsl:stylesheet version="2.0"
	xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:v12="http://aoa.de/ei/foundation/v1">

	<xsl:param name="param1" select="4711"/>
	<xsl:param name="header" />

	<xsl:template match="v12:expectedResponseTimeInMillis">
		<v12:expectedResponseTimeInMillis>
			<xsl:value-of select="$param1" />
		</v12:expectedResponseTimeInMillis>
	</xsl:template>
	<xsl:template match="v12:replyContext" />
	<xsl:template match="v12:messageHeader" >
		<xsl:if test="$header!=''">
			<xsl:comment select="$header/*[1]/text()"/>
		</xsl:if>
		<xsl:copy>
			<xsl:apply-templates select="@*|node()" />
		</xsl:copy>
	</xsl:template>
	<xsl:template match="@*|node()">
		<xsl:copy>
			<xsl:apply-templates select="@*|node()" />
		</xsl:copy>
	</xsl:template>
</xsl:stylesheet>