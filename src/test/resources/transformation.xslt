<xsl:stylesheet version="2.0"
	xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:v12="http://aoa.de/ei/foundation/v1" xmlns:fn-artofarc="http://artofarc.com/xpath-extension">

	<xsl:param name="param1" select="4711"/>
	<xsl:param name="header" />

	<xsl:template match="v12:expectedResponseTimeInMillis">
		<v12:expectedResponseTimeInMillis>
			<xsl:value-of select="$param1" />
		</v12:expectedResponseTimeInMillis>
	</xsl:template>
	<xsl:template match="v12:replyContext" />
	<xsl:template match="v12:messageHeader" >
		<xsl:comment select="fn-artofarc:uuid()"/>
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