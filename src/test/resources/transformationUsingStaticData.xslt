<xsl:stylesheet version="1.0"
	xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:v12="http://aoa.de/ei/foundation/v1">
	
	<xsl:import href="transformation.xslt"/>

	<xsl:variable name="staticData" select="document('/data/static.xml')"/>
	
	<xsl:template match="v12:replyContext">
		<v12:replyContext>
			<xsl:value-of select="$staticData/*[1]/text()" />
		</v12:replyContext>
	</xsl:template>
</xsl:stylesheet>