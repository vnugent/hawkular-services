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

  <!-- take the feedId from the ${hawkular.rest.feedId} property -->
  <xsl:template match="//*[local-name()='storage-adapter']">
    <xsl:copy>
      <xsl:apply-templates select="node()|comment()|@*" />
      <xsl:attribute name="feed-id">${hawkular.rest.feedId}</xsl:attribute>
    </xsl:copy>
  </xsl:template>

  <!-- remove all resourceTypeSets except for Main -->
  <xsl:template match="//*[local-name()='managed-servers']/*[local-name()='local-dmr']">
    <xsl:copy>
      <xsl:apply-templates select="node()|comment()|@*" />
      <xsl:attribute name="resource-type-sets">Standalone Environment</xsl:attribute>
    </xsl:copy>
  </xsl:template>

  <xsl:template match="//*[local-name()='metric-set-dmr']/*[local-name()='metric-dmr' and @name='Heap Used']">
    <xsl:copy>
      <xsl:apply-templates select="node()|comment()|@*" />
      <xsl:attribute name="interval">5</xsl:attribute>
      <xsl:attribute name="time-units">seconds</xsl:attribute>
    </xsl:copy>
  </xsl:template>

  <xsl:template match="//*[local-name()='platform']/*[local-name()='memory']">
    <xsl:copy>
      <xsl:apply-templates select="node()|comment()|@*" />
      <xsl:attribute name="interval">5</xsl:attribute>
      <xsl:attribute name="time-units">seconds</xsl:attribute>
    </xsl:copy>
  </xsl:template>

  <!-- ping more frequently -->
  <xsl:template match="//*[local-name()='profile']/*[local-name()='subsystem' and @auto-discovery-scan-period-secs]">
    <xsl:copy>
      <xsl:attribute name="ping-period-secs">5</xsl:attribute>
      <xsl:apply-templates select="node()|comment()|@*" />
    </xsl:copy>
  </xsl:template>

  <!-- copy everything else as-is -->
  <xsl:template match="node()|comment()|@*">
    <xsl:copy>
      <xsl:apply-templates select="node()|comment()|@*" />
    </xsl:copy>
  </xsl:template>

</xsl:stylesheet>
