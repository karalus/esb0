<xsl:stylesheet version="1.0" xmlns="http://www.artofarc.com/esb/service" xmlns:ns="http://www.artofarc.com/esb/service" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" exclude-result-prefixes="ns">
	<xsl:output method="xml" omit-xml-declaration="yes" />
	<xsl:template match="@*|node()">
		<xsl:copy>
			<xsl:apply-templates select="@*|node()" />
		</xsl:copy>
	</xsl:template>
	<xsl:template match="@clearAll2">
		<xsl:attribute name="clearHeaders">
			<xsl:value-of select="." />
		</xsl:attribute>
	</xsl:template>
	<xsl:template match="ns:setMessage2">
		<alterMessage>
			<xsl:apply-templates select="@*|node()" />
		</alterMessage>
	</xsl:template>
	<xsl:template match="ns:dataSource">
		<jndiObjectFactory name="{@name}" classLoader="/esb0-utils/esb0-utils" type="javax.sql.DataSource" esb0Factory="com.artofarc.esb.utils.artifact.HikariDataSourceFactory" adminPostAction="hikariPoolMXBean.softEvictConnections" xmlns="http://www.artofarc.com/esb/service">
			<xsl:apply-templates select="node()" />
		</jndiObjectFactory>
	</xsl:template>
	<xsl:template match="ns:jar[text()='esb0-utils.jar']">
		<xsl:copy>esb0-utils-1.1.jar</xsl:copy>
	</xsl:template>
</xsl:stylesheet>
