<xsl:stylesheet version="2.0" xmlns="http://www.artofarc.com/esb/service" xmlns:ns="http://www.artofarc.com/esb/service" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" exclude-result-prefixes="ns">
	<xsl:param name="ext"/>
	<xsl:template match="@*|node()">
		<xsl:copy>
			<xsl:apply-templates select="@*|node()" />
		</xsl:copy>
	</xsl:template>
	<xsl:template match="@minPoolSize">
		<xsl:attribute name="minPool" select="." />
	</xsl:template>
	<xsl:template match="@maxPoolSize">
		<xsl:attribute name="maxPool" select="." />
	</xsl:template>
	<xsl:template match="@clearHeaders">
		<xsl:attribute name="clearAll" select="." />
	</xsl:template>
	<xsl:template match="@connectTimeout">
		<xsl:attribute name="connectionTimeout">
			<xsl:value-of select="." />
		</xsl:attribute>
	</xsl:template>
	<xsl:template match="@action">
		<xsl:attribute name="verb" select="." />
	</xsl:template>
	<xsl:template match="@batchSize"/>
	<xsl:template match="@overwriteContentType"/>
	<xsl:template match="ns:update">
		<setMessage>
			<xsl:apply-templates select="@*|node()" />
		</setMessage>
	</xsl:template>
	<xsl:template match="ns:executeAction">
		<executeJava>
			<xsl:apply-templates select="@*|node()" />
		</executeJava>
	</xsl:template>
	<xsl:template match="ns:parameter">
		<jdbcParameter>
			<xsl:apply-templates select="@*|node()" />
		</jdbcParameter>
	</xsl:template>
	<xsl:template match="ns:jndiObjectFactory[$ext='dsdef' and @esb0Factory='com.artofarc.esb.utils.artifact.HikariDataSourceFactory']">
		<dataSource name="{@name}">
			<xsl:apply-templates select="node()" />
		</dataSource>
	</xsl:template>
	<xsl:template match="ns:jar[text()='esb0-utils-1.1.jar']">
		<xsl:copy>esb0-utils.jar</xsl:copy>
	</xsl:template>
</xsl:stylesheet>
