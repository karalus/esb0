<xsl:stylesheet version="2.0" xmlns="http://www.artofarc.com/esb/service" xmlns:ns="http://www.artofarc.com/esb/service" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" exclude-result-prefixes="ns">
	<xsl:output method="xml" omit-xml-declaration="yes" />
	<xsl:template match="@*|node()">
		<xsl:copy>
			<xsl:apply-templates select="@*|node()" />
		</xsl:copy>
	</xsl:template>
	<xsl:template match="@clearAll">
		<xsl:attribute name="clearHeaders">
			<xsl:value-of select="." />
		</xsl:attribute>
	</xsl:template>
	<xsl:template match="@verb">
		<xsl:attribute name="action">
			<xsl:value-of select="." />
		</xsl:attribute>
	</xsl:template>
	<xsl:template match="ns:setMessage">
		<update>
			<xsl:apply-templates select="@*|node()" />
		</update>
	</xsl:template>
	<xsl:template match="ns:executeJava">
		<executeAction>
			<xsl:apply-templates select="@*|node()" />
		</executeAction>
	</xsl:template>
	<xsl:template match="ns:jdbcParameter">
		<parameter>
			<xsl:apply-templates select="@*|node()" />
		</parameter>
	</xsl:template>
	<xsl:template match="ns:dataSource">
		<jndiObjectFactory name="{@name}" classLoader="/esb0-utils/esb0-utils" type="javax.sql.DataSource" esb0Factory="com.artofarc.esb.utils.artifact.HikariDataSourceFactory" adminPostAction="hikariPoolMXBean.softEvictConnections">
			<xsl:apply-templates select="node()" />
		</jndiObjectFactory>
	</xsl:template>
	<xsl:template match="ns:jar[text()='esb0-utils.jar']">
		<xsl:copy>esb0-utils-1.1.jar</xsl:copy>
	</xsl:template>
	<xsl:template match="ns:body[contains(text(),'${body.toString}')]">
		<xsl:copy><xsl:value-of select="replace(text(),'\{body\.toString\}','{body}')" /></xsl:copy>
	</xsl:template>
</xsl:stylesheet>
