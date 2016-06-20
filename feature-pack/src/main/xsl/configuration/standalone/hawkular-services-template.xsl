<?xml version="1.0" encoding="UTF-8"?>
<!--

    Copyright 2016 Red Hat, Inc. and/or its affiliates
    and other contributors as indicated by the @author tags.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

-->

<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:xalan="http://xml.apache.org/xalan" version="2.0" exclude-result-prefixes="xalan">

  <xsl:output method="xml" version="1.0" encoding="UTF-8" indent="yes" xalan:indent-amount="4" standalone="no" />

  <xsl:param name="hawkular.agent.enabled" select="'true'"/>
  <xsl:param name="hawkular.rest.user" select="''"/>
  <xsl:param name="hawkular.rest.password" select="''"/>

  <!-- Add the default user and password if they were passed in through the parameters -->
  <xsl:template match="/*[local-name()='server']/*[local-name()='management']">
    <xsl:message>hawkular.rest.user = <xsl:value-of select="$hawkular.rest.user" /></xsl:message>
    <system-properties>
      <xsl:element name="property" namespace="{namespace-uri()}">
        <xsl:attribute name="name">hawkular.agent.enabled</xsl:attribute>
        <xsl:attribute name="value">${hawkular.agent.enabled:<xsl:value-of select="$hawkular.agent.enabled" />}</xsl:attribute>
      </xsl:element>
      <xsl:if test="$hawkular.rest.user != ''">
        <xsl:element name="property" namespace="{namespace-uri()}">
          <xsl:attribute name="name">hawkular.rest.user</xsl:attribute>
          <xsl:attribute name="value">${hawkular.rest.user:<xsl:value-of select="$hawkular.rest.user" />}</xsl:attribute>
        </xsl:element>
      </xsl:if>
      <xsl:if test="$hawkular.rest.password != ''">
        <xsl:element name="property" namespace="{namespace-uri()}">
          <xsl:attribute name="name">hawkular.rest.password</xsl:attribute>
          <xsl:attribute name="value">${hawkular.rest.password:<xsl:value-of select="$hawkular.rest.password" />}</xsl:attribute>
        </xsl:element>
      </xsl:if>
    </system-properties>
    <xsl:copy>
      <xsl:apply-templates select="node()|comment()|@*"/>
    </xsl:copy>
  </xsl:template>

  <!-- copy everything else as-is -->
  <xsl:template match="node()|comment()|@*">
    <xsl:copy>
      <xsl:apply-templates select="node()|comment()|@*" />
    </xsl:copy>
  </xsl:template>

</xsl:stylesheet>
