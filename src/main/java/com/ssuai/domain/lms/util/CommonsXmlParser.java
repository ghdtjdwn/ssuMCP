package com.ssuai.domain.lms.util;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import com.ssuai.global.exception.ConnectorParseException;

public final class CommonsXmlParser {

    private CommonsXmlParser() {
    }

    public static ParsedContent parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new ConnectorParseException();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            if (doc.getDocumentElement() == null
                    || !"content".equalsIgnoreCase(doc.getDocumentElement().getTagName())) {
                throw new ConnectorParseException();
            }
            String downloadUri = getFirstElementText(doc, "content_download_uri");
            String title = getFirstElementText(doc, "title");

            if (downloadUri != null) {
                // DOM unescapes &amp;; keep compatibility with historically double-escaped payloads.
                downloadUri = downloadUri.replace("&amp;", "&");
            }

            return new ParsedContent(title, downloadUri);
        } catch (Exception e) {
            throw new ConnectorParseException(e);
        }
    }

    private static String getFirstElementText(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            String text = nodes.item(0).getTextContent();
            if (text != null) {
                return text.trim();
            }
        }
        return null;
    }

    public record ParsedContent(String title, String downloadUri) {}
}
