package com.atex.onecms.app.dam.integration.camel.component.escenic;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import com.atex.onecms.app.dam.engagement.EngagementDesc;
import com.atex.onecms.app.dam.engagement.EngagementElement;
import com.atex.onecms.app.dam.integration.camel.component.escenic.exception.EscenicException;
import com.atex.onecms.app.dam.integration.camel.component.escenic.exception.EscenicResponseException;
import com.atex.onecms.app.dam.integration.camel.component.escenic.exception.FailedToDeserializeContentException;
import com.atex.onecms.app.dam.integration.camel.component.escenic.exception.FailedToRetrieveEscenicContentException;
import com.atex.onecms.app.dam.integration.camel.component.escenic.exception.FailedToSendContentToEscenicException;
import com.atex.onecms.app.dam.integration.camel.component.escenic.model.Content;
import com.atex.onecms.app.dam.integration.camel.component.escenic.model.Control;
import com.atex.onecms.app.dam.integration.camel.component.escenic.model.Entry;
import com.atex.onecms.app.dam.integration.camel.component.escenic.model.Feed;
import com.atex.onecms.app.dam.integration.camel.component.escenic.model.Field;
import com.atex.onecms.app.dam.integration.camel.component.escenic.model.Group;
import com.atex.onecms.app.dam.integration.camel.component.escenic.model.Link;
import com.atex.onecms.app.dam.integration.camel.component.escenic.model.Payload;
import com.atex.onecms.app.dam.integration.camel.component.escenic.model.Publication;
import com.atex.onecms.app.dam.integration.camel.component.escenic.model.Query;
import com.atex.onecms.app.dam.integration.camel.component.escenic.model.State;
import com.atex.onecms.app.dam.integration.camel.component.escenic.model.Summary;
import com.atex.onecms.app.dam.integration.camel.component.escenic.model.Title;
import com.atex.onecms.app.dam.integration.camel.component.escenic.model.Value;
import com.atex.onecms.app.dam.integration.camel.component.escenic.ws.EscenicResource;
import com.atex.onecms.app.dam.standard.aspects.OneArticleBean;
import com.atex.onecms.app.dam.standard.aspects.OneContentBean;
import com.atex.onecms.app.dam.util.DamEngagementUtils;
import com.atex.onecms.content.ContentId;
import com.atex.onecms.content.ContentManager;
import com.atex.onecms.content.ContentResult;
import com.atex.onecms.content.ContentVersionId;
import com.atex.onecms.content.IdUtil;
import com.atex.onecms.content.Subject;
import com.atex.onecms.content.repository.StorageException;
import com.atex.onecms.ws.service.ErrorResponseException;
import com.atex.plugins.structured.text.StructuredText;
import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import com.polopoly.cm.policy.PolicyCMServer;
import com.sun.xml.bind.marshaller.CharacterEscapeHandler;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.httpclient.util.URIUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * @author jakub
 */
public class EscenicUtils {

	private static String AUTH_PREFIX = "Basic ";
	private static final java.util.logging.Logger LOGGER = Logger.getLogger(EscenicUtils.class.getName());
	protected static final String INLINE_RELATIONS_GROUP = "com.escenic.inlineRelations";
	protected static final String PICTURE_RELATIONS_GROUP = "pictureRel";
	protected static final String THUMBNAIL_RELATION_GROUP = "thumbnail";
	protected static final String APP_ATOM_XML = "application/atom+xml";
	protected static final String ATOM_APP_ENTRY_TYPE = APP_ATOM_XML + "; type=entry";
	protected static final String RELATED = "related";
	protected static final String PUBLISHED_STATE = "published";
	protected static final int MAX_CONNECTIONS = 5;
	protected EscenicConfig escenicConfig;
	protected ContentManager contentManager;
	protected DamEngagementUtils engagementUtils;
	protected PolicyCMServer cmServer;

	public CloseableHttpClient getHttpClient() {
		return httpClient;
	}

	protected CloseableHttpClient httpClient;


	public EscenicUtils(EscenicConfig escenicConfig, ContentManager contentManager, PolicyCMServer cmServer) {
		final RequestConfig config = RequestConfig.custom()
				.setConnectTimeout(escenicConfig.getTimeout())
				.setConnectionRequestTimeout(escenicConfig.getTimeout())
				.setSocketTimeout(escenicConfig.getTimeout()).build();

		this.escenicConfig = escenicConfig;
		this.contentManager = contentManager;
		this.cmServer = cmServer;
		this.httpClient = HttpClients.custom()
			.disableAutomaticRetries()
			.disableCookieManagement()
			.disableContentCompression()
			.setDefaultRequestConfig(config)
			.setMaxConnPerRoute(MAX_CONNECTIONS)
			.setMaxConnTotal(MAX_CONNECTIONS)
			.build();
		LOGGER.setLevel(Level.FINEST);
	}
	/**
	 * Test interface only
	 */
	protected EscenicUtils(){

	}

	public String retrieveEscenicItem(String location) throws FailedToRetrieveEscenicContentException {
		HttpGet request = new HttpGet(location);
		request.setHeader(generateAuthenticationHeader(escenicConfig.getUsername(), escenicConfig.getPassword()));
		try (CloseableHttpResponse result = httpClient.execute(request);){
			String xml = null;
			if (result.getEntity() != null) {
				xml = EntityUtils.toString(result.getEntity(), StandardCharsets.UTF_8);
			}

			if (LOGGER.isLoggable(Level.FINEST)) {
				LOGGER.finest("Retrieved escenic item:\n" + xml);
			}
			return xml;
		} catch (Exception e) {
			LOGGER.severe("An error occurred when attempting to retrieve content from escenic at location: " + location);
			throw new FailedToRetrieveEscenicContentException("An error occurred when attempting to retrieve content from escenic at location: " + location + " due to : " + e);
		} finally {
			request.releaseConnection();
		}
	}

	protected Entry generateExistingEscenicEntry(String location) throws FailedToRetrieveEscenicContentException, FailedToDeserializeContentException {
		Entry entry = null;
		if (StringUtils.isNotEmpty(location)) {
			String xml = retrieveEscenicItem(location);
			if (LOGGER.isLoggable(Level.FINEST)) {
				LOGGER.finest("Result on GET on location : " + location + " from escenic:\n" + xml);
			}
			entry = deserializeXml(xml);
		}
		return entry;
	}

	private boolean isValueEscaped(String fieldName) {

		if (StringUtils.isNotEmpty(fieldName)) {
			switch (fieldName) {
				case "body":
				case "embedCode":
				case "autocrop":
				case "summary":
				case "title":
				case "headline":
					return true;
				default:
					return false;
			}
		}
		return false;
	}

	protected Value createValue(String fieldName, Object value) {
		if (value != null) {
			if (value instanceof String) {
				if (!isValueEscaped(fieldName)) {
					value = escapeXml(value.toString());
				}
			}
			return new Value(Arrays.asList(new Object[]{value}));
		} else {
			return null;
		}
	}

    protected Field createField(String fieldName,
                                Object value,
                                List<Field> fields,
                                com.atex.onecms.app.dam.integration.camel.component.escenic.model.List list) {

		return new Field(fieldName, createValue(fieldName, value), fields, list);
	}

	protected String wrapWithDiv(String text) {
		return "<div xmlns=\"http://www.w3.org/1999/xhtml\">" + text + "</div>";
	}

	protected String wrapWithCDATA(String text) {
		return "<![CDATA[" + text + "]]>";
	}

	protected String processAndReplaceOvermatterAndNoteTags(String structuredText) {
		structuredText = replaceLineSeparatorWithSpace(structuredText);
		return processPseriesTagsToHtml(structuredText);
	}

	protected org.jsoup.nodes.Document extractOvermatterAndNotesTags(String html) {
			org.jsoup.nodes.Document doc = Jsoup.parseBodyFragment(Strings.nullToEmpty(html));

			//process overmatter span tags
			for (final Element element : doc.select("span.x-atex-overmatter")) {

				Element parent = element.parent();
				if (parent != null) {
					for(org.jsoup.nodes.Node node : element.childNodesCopy()){
						parent.appendChild(node);
					}
				}
				element.remove();
			}

			//process script tags (atex notes)
			for (final Element element : doc.select("script")) {
				if (element.hasAttr("type") && StringUtils.equalsIgnoreCase(element.attr("type"), "text/atex-note")) {
					element.remove();
				}
			}

			return doc;
	}

	protected String processPseriesTagsToHtml(final String html) {
		final org.jsoup.nodes.Document doc = extractOvermatterAndNotesTags(html);

			doc.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml);
			doc.outputSettings().prettyPrint(false);

		return doc.body().html();
	}

	protected String convertStructuredTextToEscenic(String structuredText, List<EscenicContent> escenicContentList) {
		if (LOGGER.isLoggable(Level.FINEST)) {
			LOGGER.finest("Body before replacing embeds: " + structuredText);
		}

		structuredText = wrapWithDiv(structuredText);
		structuredText = processAndReplaceOvermatterAndNoteTags(structuredText);

		if (escenicContentList != null && !escenicContentList.isEmpty()) {
			structuredText = EscenicSocialEmbedProcessor.getInstance().replaceEmbeds(structuredText, escenicContentList);
		} else {
			//still ensure it's parsed

			final org.jsoup.nodes.Document doc = Jsoup.parseBodyFragment(structuredText);
			doc.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml);
			structuredText = doc.body().html();
		}

		if (LOGGER.isLoggable(Level.FINEST)) {
			LOGGER.finest("Body after replacing embeds: " + structuredText);
		}
		return structuredText;
	}

	protected String getStructuredText(StructuredText str) {
		if (str != null)
			return str.getText();
		else
			return "";
	}

	//remove html tags and replace non breaking spaces
	protected String removeHtmlTags(String text) {
		if (StringUtils.isNotBlank(text)) {
			org.jsoup.nodes.Document d = Jsoup.parseBodyFragment(text);
			d.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml);
			return d.body().text();
		}
		return text;

	}

	protected String replaceLineSeparatorWithSpace(String text) {
		if (StringUtils.isNotBlank(text)) {
			text = text.replaceAll("\u2028", " ");
			return text.replaceAll("&#8232", " ");
		}
		return text;
	}

	//remove html tags and replace non breaking spaces
	protected String replaceNonBreakingSpaces(String text) {
		if (StringUtils.isNotBlank(text)) {
			text = text.replaceAll("\u00a0", "");
			return text.replaceAll("&nbsp;", "");
		}
		return text;

	}

	protected static StringEntity generateAtomEntity(String xmlContent) {
		StringEntity entity = new StringEntity(xmlContent, StandardCharsets.UTF_8);
		entity.setContentType(APP_ATOM_XML);
		return entity;
	}

	public InputStreamEntity generateImageEntity(InputStream in, String mimeType) {
		final ContentType contentType = getContentTypeForMimeType(mimeType);
		final InputStreamEntity inputStreamEntity = new InputStreamEntity(in, contentType);
		return inputStreamEntity;
	}

	public ContentType getContentTypeForMimeType(String mimeType) {
		return ContentType.create(
			Optional.ofNullable(mimeType)
				.orElse(ContentType.APPLICATION_OCTET_STREAM.getMimeType()));
	}

	protected Header generateContentTypeHeader(String contentType) {
		return new BasicHeader(HttpHeaders.CONTENT_TYPE, contentType);
	}

	public Header generateAuthenticationHeader(String username, String password) throws RuntimeException {
		if (StringUtils.isEmpty(username) && StringUtils.isEmpty(password)) {
			throw new RuntimeException("Unable to access username & password for escenic");
		}

		String encoding = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

		return new BasicHeader(HttpHeaders.AUTHORIZATION, AUTH_PREFIX + encoding);
	}

	protected CloseableHttpResponse sendNewContentToEscenic(String xmlContent, Websection websection) throws FailedToSendContentToEscenicException {

		if (websection == null) {
			throw new RuntimeException("Failed to extract section id - websection was blank.");
		}

		if (StringUtils.isBlank(websection.getEscenicId())) {
			throw new RuntimeException("Unable to send content to escenic due to blank section id");
		}

		if (StringUtils.isBlank(escenicConfig.getApiUrl())) {
			throw new RuntimeException("Blank or missing escenic ApiUrl");
		}

		String url = escenicConfig.getApiUrl() + "/webservice/escenic/section/" + websection.getEscenicId() + "/content-items";

		HttpPost request = new HttpPost(url);
		request.setEntity(generateAtomEntity(xmlContent));
		request.expectContinue();
		request.setHeader(generateAuthenticationHeader(escenicConfig.getUsername(), escenicConfig.getPassword()));
		request.setHeader(generateContentTypeHeader(APP_ATOM_XML));

		if (LOGGER.isLoggable(Level.FINEST)) {
			LOGGER.finest("Sending the following xml to " + url + ":\n" + xmlContent);
		}

		try (CloseableHttpResponse result = httpClient.execute(request);){
			logXmlContentIfFailure(result, xmlContent, url);
			return result;
		} catch (Exception e) {
			throw new FailedToSendContentToEscenicException("Failed to send new content to " + url + ": " + e);
		} finally {
			request.releaseConnection();
		}
	}

	protected void logXmlContentIfFailure(CloseableHttpResponse result, String xmlContent, String url) {
		int statusCode = result.getStatusLine().getStatusCode();

		if (statusCode != HttpStatus.SC_CREATED && statusCode != HttpStatus.SC_OK && statusCode !=HttpStatus.SC_NO_CONTENT) {
			LOGGER.severe("Failed to POST the following to " + url + ":\n" + xmlContent);
			LOGGER.severe("Failure code is : " + statusCode);
			LOGGER.severe("Failure reason is : " + result.getStatusLine().getReasonPhrase());
		}
	}

	protected CloseableHttpResponse sendUpdatedContentToEscenic(String url, String xmlContent) throws FailedToSendContentToEscenicException {
		HttpPut request = new HttpPut(url);
		request.setEntity(generateAtomEntity(xmlContent));
		request.expectContinue();
		request.setHeader(generateAuthenticationHeader(escenicConfig.getUsername(), escenicConfig.getPassword()));
		request.setHeader(generateContentTypeHeader(APP_ATOM_XML));
		request.setHeader(HttpHeaders.IF_MATCH, "*");

		if (LOGGER.isLoggable(Level.FINEST)) {
			LOGGER.finest("Posting the following to " + url + ":\n" + xmlContent);
		}

		try (CloseableHttpResponse result = httpClient.execute(request);){
			logXmlContentIfFailure(result, xmlContent, url);
			return result;
		} catch (Exception e) {
			throw new FailedToSendContentToEscenicException("Failed to send an update to " + url + " due to: " + e);
		} finally {
			request.releaseConnection();
		}
	}

	public static Entry deserializeXml(String xml) throws FailedToDeserializeContentException {
		Entry entry = null;
		try {
			JAXBContext jaxbContext = JAXBContext.newInstance(Entry.class);
			Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
			entry = (Entry) unmarshaller.unmarshal(new StringReader(xml));

		} catch (JAXBException e) {
			throw new FailedToDeserializeContentException("Failed to deserialize content: " + e);
		}

		return entry;
	}

	protected static String serializeXml(Object object) {
		StringWriter stringWriter = new StringWriter();
		try {
			JAXBContext jaxbContext = JAXBContext.newInstance(Entry.class);
			Marshaller marshaller = jaxbContext.createMarshaller();
			marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
			marshaller.setProperty(CharacterEscapeHandler.class.getName(), new CustomCharacterEscapeHandler());
			marshaller.marshal(object, new StreamResult(stringWriter));
			return stringWriter.getBuffer().toString();

		} catch (Exception e) {
			throw new RuntimeException("An error occurred during serialization");
		} finally {
			if (stringWriter.getBuffer() != null) {
				return stringWriter.getBuffer().toString();
			} else {
				return null;
			}
		}
	}

	protected Control generateControl(String draft, String stateText) {
		Control control = new Control();
		control.setDraft(draft);
		control.setState(generateState(stateText));
		return control;
	}

	protected List<State> generateState(String stateText) {
		State state = new State();
		state.setState(stateText);
		state.setName(stateText);
		return Arrays.asList(state);
	}

	private String clean(String component) {
		if (component == null) return null;
		return CharMatcher.ASCII.retainFrom(component);
	}

	public List<Link> generateLinks(EscenicContent escenicContent, Websection websection) {
		List<Link> links = new ArrayList<>();
		if (escenicContent != null) {

			if (escenicContent instanceof EscenicImage) {
				EscenicImage escenicImage = (EscenicImage) escenicContent;
				Link link = createLink(null, THUMBNAIL_RELATION_GROUP, escenicImage.getThumbnailUrl(), EscenicImage.THUMBNAIL_MODEL_TYPE,
					null, null, null, null, null, null, websection);

				if (link != null) {
					links.add(link);
				}

				if (escenicImage.isTopElement()) {
					List<Field> fields = new ArrayList<>();
					fields.add(createField("title", escapeXml(escenicImage.getTitle()), null, null));
					fields.add(createField("caption", escapeXml(escenicImage.getCaption()), null, null));
					Link topElementLink = createLink(fields, PICTURE_RELATIONS_GROUP, escenicImage.getThumbnailUrl(), EscenicImage.IMAGE_MODEL_CONTENT_SUMMARY,
						escenicImage.getEscenicLocation(), ATOM_APP_ENTRY_TYPE, RELATED, escenicImage.getEscenicId(),
						escenicImage.getTitle(), PUBLISHED_STATE, websection);

					if (topElementLink != null) {
						links.add(topElementLink);
					}
				}

				if (escenicImage.isInlineElement()) {
					List<Field> fields = new ArrayList<>();
					fields.add(createField("title", escapeXml(escenicImage.getTitle()), null, null));
					fields.add(createField("caption", escapeXml(escenicImage.getCaption()), null, null));
					Link inlineElementLink = createLink(fields, INLINE_RELATIONS_GROUP, escenicImage.getThumbnailUrl(), escenicImage.IMAGE_MODEL_CONTENT_SUMMARY,
						escenicImage.getEscenicLocation(), ATOM_APP_ENTRY_TYPE, RELATED, escenicImage.getEscenicId(),
						escenicImage.getTitle(), PUBLISHED_STATE, websection);

					if (inlineElementLink != null) {
						links.add(inlineElementLink);
					}

				}

			} else if (escenicContent instanceof EscenicGallery) {
				EscenicGallery escenicGallery = (EscenicGallery) escenicContent;
				Link link = createLink(null, THUMBNAIL_RELATION_GROUP, escenicGallery.getThumbnailUrl(), EscenicImage.THUMBNAIL_MODEL_TYPE,
					null, null, null, null, null, null, websection);

				if (link != null) {
					links.add(link);
				}

				if (escenicGallery.isTopElement()) {
					List<Field> fields = new ArrayList<>();
					fields.add(createField("title", escapeXml(escenicGallery.getTitle()), null, null));
					Link topElementLink = createLink(fields, PICTURE_RELATIONS_GROUP, escenicGallery.getThumbnailUrl(), EscenicGallery.GALLERY_MODEL_CONTENT_SUMMARY,
						escenicGallery.getEscenicLocation(), ATOM_APP_ENTRY_TYPE, RELATED, escenicGallery.getEscenicId(),
						escenicGallery.getTitle(), PUBLISHED_STATE, websection);
					if (topElementLink != null) {
						links.add(topElementLink);
					}
				}

				if (escenicContent.isInlineElement()) {
					List<Field> fields = new ArrayList<>();
					fields.add(createField("title", escapeXml(escenicGallery.getTitle()), null, null));
					Link inlineElementLink = createLink(fields, INLINE_RELATIONS_GROUP, escenicGallery.getThumbnailUrl(), EscenicGallery.GALLERY_MODEL_CONTENT_SUMMARY,
						escenicGallery.getEscenicLocation(), ATOM_APP_ENTRY_TYPE, RELATED, escenicGallery.getEscenicId(),
						escenicGallery.getTitle(), PUBLISHED_STATE, websection);

					if (inlineElementLink != null) {
						links.add(inlineElementLink);
					}
				}

			} else if (escenicContent instanceof EscenicEmbed) {
				EscenicEmbed escenicEmbed = (EscenicEmbed) escenicContent;
				List<Field> fields = new ArrayList<>();
				fields.add(createField("title", escapeXml(escenicEmbed.getTitle()), null, null));
				Link link = createLink(fields, INLINE_RELATIONS_GROUP, null, EscenicEmbed.EMBED_MODEL_CONTENT_SUMMARY,
					escenicEmbed.getEscenicLocation(), ATOM_APP_ENTRY_TYPE, RELATED, escenicEmbed.getEscenicId(),
					escenicEmbed.getTitle(), PUBLISHED_STATE, websection);

				if (link != null) {
					links.add(link);
				}
			} else if (escenicContent instanceof EscenicContentReference) {
				EscenicContentReference escenicContentReference = (EscenicContentReference) escenicContent;
				List<Field> fields = new ArrayList<>();
				fields.add(createField("title", escapeXml(escenicContentReference.getTitle()), null, null));

				if (escenicContentReference.isTopElement()) {
					Link topElementLink = createLink(fields, PICTURE_RELATIONS_GROUP, escenicContentReference.getThumbnailUrl(), EscenicContentReference.MODEL_CONTENT_SUMMARY_PREFIX + escenicContentReference.getType() ,
						escenicContentReference.getEscenicLocation(), ATOM_APP_ENTRY_TYPE, RELATED, escenicContentReference.getEscenicId(),
						escenicContentReference.getTitle(), PUBLISHED_STATE, websection);
					if (topElementLink != null) {
						links.add(topElementLink);
					}
				}
				if (escenicContent.isInlineElement()) {
					Link inlineLink = createLink(fields, INLINE_RELATIONS_GROUP, null,  EscenicContentReference.MODEL_CONTENT_SUMMARY_PREFIX + escenicContentReference.getType(),
						escenicContentReference.getEscenicLocation(), ATOM_APP_ENTRY_TYPE, RELATED, escenicContentReference.getEscenicId(),
						escenicContentReference.getTitle(), PUBLISHED_STATE, websection);
					if (inlineLink != null) {
						links.add(inlineLink);
					}
				}
			}
		}

		return links;

	}

    protected Link createLink(List<Field> fields,
                              String group,
                              String thumbnail,
                              String model,
                              String href,
                              String type,
                              String rel,
                              String identifier,
                              String title,
                              String state,
                              Websection websection) {

		Link link = new Link();
		Payload payload = new Payload();
		payload.setField(fields);
		payload.setModel(getEscenicModel(websection, model));

		if (!StringUtils.isBlank(thumbnail)) {
			link.setThumbnail(thumbnail);
		}

		link.setGroup(group);
		link.setHref(href);
		link.setType(type);
		link.setRel(rel);
		link.setState(state);
		link.setPayload(payload);
		link.setIdentifier(identifier);
		link.setTitle(escapeXml(title));
		return link;
	}

	protected String escapeXml(String text) {
		return StringEscapeUtils.escapeXml10(removeHtmlTags(text));
	}

	protected static Object extractContentBean(ContentResult contentCr) {
		if (contentCr != null) {
			com.atex.onecms.content.Content content = contentCr.getContent();
			if (content != null) {
				Object contentData = content.getContentData();
				if (contentData != null) {
					return contentData;
				}
			}
		} else {
			throw new RuntimeException("Unable to access content result");
		}
		return null;
	}

	protected static ContentResult checkAndExtractContentResult(ContentId contentId, ContentManager contentManager) throws RuntimeException {

		ContentResult<OneContentBean> contentCr = null;
		ContentVersionId contentVersionId = null;
		try {
			contentVersionId = contentManager.resolve(contentId, Subject.NOBODY_CALLER);
		} catch (StorageException e) {
			throw new RuntimeException("error occurred when resolving content id: " + contentId + e);
		}

		if (contentVersionId != null) {
			contentCr = contentManager.get(contentVersionId, null, OneContentBean.class, null, Subject.NOBODY_CALLER);
		} else {
			throw new RuntimeException("ContentVersionId not found");
		}

		if (contentCr != null && contentCr.getStatus().isSuccess()) {
			return contentCr;
		} else {
			throw new RuntimeException("Retrieing content failed: " + contentCr.getStatus());
		}
	}

	protected String getEscenicIdFromEngagement(EngagementDesc engagementDesc, String existingId) {
		String escenicId = existingId;
		if (engagementDesc != null) {

			if (StringUtils.isNotBlank(engagementDesc.getAppPk())) {
				escenicId = engagementDesc.getAppPk();
			}
		}

		return escenicId;
	}

	protected String getEscenicLocationFromEngagement(EngagementDesc engagementDesc, String existingLocation) {
		String escenicLocation = existingLocation;
		if (engagementDesc != null) {
			if (engagementDesc.getAttributes() != null) {
				for (EngagementElement element : engagementDesc.getAttributes()) {
					if (element != null) {
						if (StringUtils.equalsIgnoreCase(element.getName(), "location")) {
							escenicLocation = element.getValue();
						}
					}
				}
			}
		}
		return escenicLocation;
	}

	public List<Link> mergeLinks(List<Link> existingLinks, List<Link> links) {
		if (existingLinks != null && links != null) {
			for (Link existinglink : existingLinks) {

				boolean found = false;

				for (Link link : links) {
					if (link.equals(existinglink)) {
						found = true;
					}
				}

				if (!found && shouldLinkRelationBeAdded(existinglink)) {
					existinglink.setTitle(escapeXml(existinglink.getTitle()));
					links.add(existinglink);
				}
			}
			return links;
		}

		if (existingLinks != null) {
			for (Link exLink : existingLinks) {
				exLink.setTitle(escapeXml(exLink.getTitle()));
			}
		}

		return existingLinks;
	}

	private boolean shouldLinkRelationBeAdded(Link existinglink) {
		if (existinglink != null) {
			return (!StringUtils.equalsIgnoreCase(existinglink.getGroup(), "pictureRel") &&
				    !StringUtils.equalsIgnoreCase(existinglink.getGroup(), "com.escenic.inlineRelations"));
		}

		return false;
	}

	public String extractIdFromLocation(String escenicLocation) {
		String id = null;
		if (StringUtils.isNotEmpty(escenicLocation)) {
			id = escenicLocation.substring(escenicLocation.lastIndexOf('/') + 1);
		}

		return id;
	}

	public String createSearchGroups(String xml) throws EscenicException {
		try {
			DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
			docFactory.setNamespaceAware(true);
			DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
			Document doc = docBuilder.parse(new InputSource(new StringReader(xml)));
			Node feed = doc.getFirstChild();
			NodeList list  = feed.getChildNodes();
			Node currentGroupNode = null;
			for (int i=0; i < list.getLength(); i++) {
				Node node = list.item(i);
				if (node != null) {
					if (StringUtils.equalsIgnoreCase(node.getNodeName(), "facet:group")) {
						currentGroupNode = node;
					}

					if (StringUtils.equalsIgnoreCase(node.getNodeName(), "opensearch:query")) {
						if (currentGroupNode != null) {
							currentGroupNode.appendChild(node);
							currentGroupNode.appendChild(doc.createTextNode("\n"));
						}
					}
				}
			}

			StringWriter stringWriter = new StringWriter();
			TransformerFactory transformerFactory = TransformerFactory.newInstance();
			Transformer transformer = transformerFactory.newTransformer();
			transformer.setOutputProperty(OutputKeys.METHOD, "xml" );
			transformer.setOutputProperty(OutputKeys.INDENT, "false" );
			DOMSource source = new DOMSource(doc);
			transformer.transform(source,  new StreamResult(stringWriter));
			return stringWriter.getBuffer().toString();
		} catch (ParserConfigurationException | TransformerException | IOException | SAXException e) {
			throw new EscenicException("Failed to process the response to search query" + e);
		}
	}

	public void processSearchInfo(Feed feed) throws URISyntaxException {
		if (feed != null) {
			List<Link> links = feed.getLink();

			if (links != null) {
				for (Link link : links) {
					processPageInfo(feed, link);
				}
			}
		}
	}

	private void processPageInfo(Feed feed, Link link) throws URISyntaxException {
		if (link != null) {
			String rel = link.getRel();
			if (StringUtils.isNotBlank(link.getHref())) {
				List<NameValuePair> params = URLEncodedUtils.parse(new URI(link.getHref()), "UTF-8");
				if (params != null) {
					if (StringUtils.isNotBlank(rel)) {
						switch (rel.toLowerCase()) {
							case "self":
								for (NameValuePair param : params) {
									if (StringUtils.equalsIgnoreCase(param.getName(), "pw")) {
										feed.setSelfPageNumber(param.getValue());
									} else if (StringUtils.equalsIgnoreCase(param.getName(), "c")) {
										feed.setSelfItemCount(param.getValue());
									}
								}
								break;
							case "first":
								for (NameValuePair param : params) {
									if (StringUtils.equalsIgnoreCase(param.getName(), "pw")) {
										feed.setFirstPageNumber(param.getValue());
									} else if (StringUtils.equalsIgnoreCase(param.getName(), "c")) {
										feed.setFirstItemCount(param.getValue());
									}
								}
								break;
							case "next":
								for (NameValuePair param : params) {
									if (StringUtils.equalsIgnoreCase(param.getName(), "pw")) {
										feed.setNextPageNumber(param.getValue());
									} else if (StringUtils.equalsIgnoreCase(param.getName(), "c")) {
										feed.setNextItemCount(param.getValue());
									}
								}
								break;
							case "last":
								for (NameValuePair param : params) {
									if (StringUtils.equalsIgnoreCase(param.getName(), "pw")) {
										feed.setLastPageNumber(param.getValue());
									} else if (StringUtils.equalsIgnoreCase(param.getName(), "c")) {
										feed.setLastItemCount(param.getValue());
									}
								}
								break;
						}
					}
				}
			}
		}
	}

	public void processFacetInfo(Feed feed) {
		if (feed != null) {
			List<Group> filteredGroups = new ArrayList<Group>();
			if (feed.getGroup() != null) {
				for (Group group : feed.getGroup()) {
					if (group != null) {
						if (!StringUtils.equalsIgnoreCase(group.getTitle(), "creationdate") && !StringUtils.equalsIgnoreCase(group.getTitle(), "publishdate")) {
							Group facetGroup = new Group();
							facetGroup.setTitle(group.getTitle());
							List<Query> queries = new ArrayList<>();
							if (group.getQuery() != null) {
								for (Query query : group.getQuery()) {
									String[] relatedArray = query.getRelated().split(" ");
									if (relatedArray != null && relatedArray.length > 0) {
										query.setRelated(relatedArray[relatedArray.length - 1]);
									}
									queries.add(query);
								}
								facetGroup.setQuery(queries);
							}
							filteredGroups.add(facetGroup);
						} else {
							filteredGroups.add(group);
						}
					}
				}
			}

			if (!filteredGroups.isEmpty()) {
				feed.setGroup(filteredGroups);
			}
		}
	}

	public String getFirstBodyParagraph(String html) {
		if (StringUtils.isNotBlank(html)) {
			Element document = Jsoup.parse(html).body();
			if (document != null) {
				for (Element element : document.children()) {
					if (StringUtils.equalsIgnoreCase(element.nodeName(), "p")) {
						if (StringUtils.isNotBlank(element.text())) {
							return processAndReplaceOvermatterAndNoteTags(element.text());
						}
					}

				}
			}

			return "";

		}

		return "";

	}

	public String removeFirstParagraph(String text) {
		org.jsoup.nodes.Document document = Jsoup.parseBodyFragment(text);
		document.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml);
		Element doc = document.body();

		if (doc != null) {
				for (Element element : doc.children()) {
					if (StringUtils.equalsIgnoreCase(element.nodeName(), "p")) {
						if (StringUtils.isNotBlank(element.text())) {
							element.remove();
							return document.body().html();
						}
					}
				}
		}

		return text;
	}

	public boolean isUpdateAllowed(Entry entry) {
		if (entry != null) {
			try {
				Content content = entry.getContent();
				if (content != null) {
					Payload payload = content.getPayload();
					if (payload != null) {
						List<Field> fields = payload.getField();
						if (fields != null) {
							for (Field field : fields) {
								if (field != null) {
									if (StringUtils.equalsIgnoreCase(field.getName(), "allowCUEUpdates")) {
										if (field.getValue() != null && field.getValue().getValue() != null) {
												for (Object o : field.getValue().getValue()) {
													if (o instanceof String) {
														String flag = o.toString();
														return StringUtils.equalsIgnoreCase(flag, "false") ? true : false;
													}
												}
										}
									}
								}
							}
						}
					}
				}
			} catch (Exception e) {
				throw new RuntimeException("Failed to extract a value for allowCUEUpdates flag." + e);
			}
		}
		return false;
	}

	public Title createTitle(String value, String type) {
		Title title = new Title();
		title.setType(type);
		title.setTitle(escapeXml(value));
		return title;
	}

	public Summary createSummary(String value, String type) {
		Summary summary = new Summary();
		summary.setType(type);
		summary.setSummary(escapeXml(value));
		return summary;
	}

	public String getQuery(String query){
		if (StringUtils.isBlank(query)) {
			query = EscenicResource.DEFAULT_QUERY;
		} else {

			query = replaceInvalidQueryChars(query);

			//process the query part here:
			String[] terms = query.split(" ");
			if (terms != null && terms.length > 1) {
				query = "((" + query + ")";
				for (int i=0; i < terms.length; i++){
					String s = terms[i];
					if (StringUtils.isNotBlank(s)) {
						if (i == 0) {
							query += " OR ((";
						} else {
							query += " (";
						}

						query += s + " OR " + s + "*)";

						if (terms.length-1 == i) {
							query += "))";
						}
					}
				}
			} else {
				query = "((" + query + ") OR (" + query + " OR " + query + "*))";
			}
		}
		return query;
	}

	private String replaceInvalidQueryChars(String query) {
		if (StringUtils.isNotBlank(query)) {
			query = query.replaceAll("!", " ");
			query = query.replaceAll("-", " ");
			query = query.replaceAll("/", " ");
			query = query.replaceAll("\\\\", "");
			query = query.replaceAll("\\(", " ");
			query = query.replaceAll("\\)", " ");
			query = query.replaceAll("\\[", " ");
			query = query.replaceAll("]", " ");
			query = query.replaceAll(";", " ");
			query = query.replaceAll(":", " ");
			query = query.replaceAll("\\?", " ");
		}

		return query;
	}

	public boolean isAlreadyProcessed(List<EscenicContent> list, CustomEmbedParser.SmartEmbed embed) {
		if (list != null && embed != null) {
			for (EscenicContent content : list) {
				if (content != null) {
					//special case for social embeds - instead of comparing the onecms id we'll be comparing the embed URL
					if (StringUtils.equalsIgnoreCase(embed.getObjType(), EscenicEmbed.SOCIAL_EMBED_TYPE) && content instanceof EscenicEmbed) {
						EscenicEmbed socialEmbed = (EscenicEmbed) content;
						if (socialEmbed != null) {
							if (StringUtils.isNotEmpty(socialEmbed.getEmbedUrl()) && StringUtils.isNotEmpty(embed.getEmbedUrl()) &&
								StringUtils.equalsIgnoreCase(socialEmbed.getEmbedUrl(), embed.getEmbedUrl())) {
								return true;
							}
						}
					} else if (content.getOnecmsContentId() != null) {
						if (embed.getContentId() != null && embed.getContentId().equals(content.getOnecmsContentId())) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	public Publication cleanUpPublication(Publication publication) {
		if (publication != null) {
			publication.setTitle(escapeXml(publication.getTitle()));
			List<Link> links = publication.getLink();

			if (links != null) {
				for (Link link : links) {
					link.setTitle(escapeXml(link.getTitle()));
				}
			}
		}
		return publication;
	}

	public void cleanUpSummary(Entry existingEntry) {
		if (existingEntry != null && existingEntry.getSummary() != null && StringUtils.isNotBlank(existingEntry.getSummary().getSummary())) {
			existingEntry.setSummary(createSummary(existingEntry.getSummary().getSummary(), "text"));
		}
	}

	public boolean isSupportedContentType(String contentType) {
		if (StringUtils.isNotBlank(contentType)) {

			switch (contentType) {
				case "Article":
				case "Video":
				case "Gallery":
				case "Code":
					return true;
			}
		}
			return false;
	}

	public String getFieldValueFromPropertyBag(OneArticleBean article, String field) {
		try {
			Map<String, Map<String, String>> propertyBag = (Map<String, Map<String, String>>) PropertyUtils.getProperty(article, "propertyBag");
			if (propertyBag != null && propertyBag.size() > 0) {
				for (String groupKey : propertyBag.keySet()) {
					if (StringUtils.equalsIgnoreCase(groupKey, "custom")) {
						Map<String, String> groupMap = propertyBag.get(groupKey);
						for (String key : groupMap.keySet()) {
							if (StringUtils.equalsIgnoreCase(key, field)) {
								return groupMap.get(key);
							}
						}
					}
				}
			} else {
				LOGGER.log(Level.SEVERE, "Failed to retrieve value from property bag - property bag doesn't exist or is empty.");
			}

		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "Failed to retrieve value from property bag", e);
		}

		return null;
	}

	public String getStructuredText(OneArticleBean article, String field) {
		try {
			Object property = PropertyUtils.getProperty(article, field);
			if (property instanceof StructuredText) {
				return getStructuredText((StructuredText) property);
			}
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "Failed to retrieve structured text field", e);
		}
		return null;
	}

	public String getField(OneArticleBean article, String field) {
		try {
			Object property = PropertyUtils.getProperty(article, field);
			if (property instanceof String) {
				return (String) property;
			}
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "Failed to retrieve value for field: " + field, e);
		}
		return null;
	}

	public boolean isBinaryField(Field field) {
		return StringUtils.isNotBlank(field.getName()) && StringUtils.equalsIgnoreCase(field.getName(), "binary");
	}

	public String getFieldRealValue(Field field) {
		if (field != null) {
			if (field.getValue() != null && field.getValue().getValue() != null) {
				for (Object o : field.getValue().getValue()) {
					if (o != null) {
						if (o instanceof String) {
							return o.toString();
						}
					}
				}
			}
		}
		return null;
	}

	public List generateUpdatedListOfFields(List<Field> existingFields, List<Field> newFields) {
		if (newFields != null && !newFields.isEmpty()) {
			if (existingFields != null && !existingFields.isEmpty()) {
				existingFields.forEach(field -> {
					AtomicBoolean managedByEscenic = new AtomicBoolean(true);
					newFields.forEach(newField -> {
						//just for the image type..
						if (!isBinaryField(field)) {
							if (field != null && field.fieldNameEqualsIgnoreCase(newField)) {
								if(field.getName().equals("com.escenic.tags")){
									field.setList(newField.getList());
								}
								field.setValue(newField.getValue());
								managedByEscenic.set(false);
							}
						}
					});

					if (!isBinaryField(field) && managedByEscenic.get()) {
						if (field != null && field.getValue() != null && field.getValue().getValue() != null && !field.getValue().getValue().isEmpty()) {
							String value = getFieldRealValue(field);
							Field escapedField = createField(field.getName(), escapeXml(value == null ? "" : value), null, null);
							field.setValue(escapedField.getValue());
						}
					}
				});
			} else {
				LOGGER.log(Level.WARNING, "Unable to generate update list of fields - existing fields list was either null or empty.");
			}
		} else {
			LOGGER.log(Level.WARNING, "Unable to generate update list of fields - new fields list was either null or empty.");
		}

		return existingFields;
	}

	public boolean checkIfEntryHasContentPayloadAndListOfFields(Entry entry) {
		if (entry != null) {
			if (entry.getContent() != null) {
				if (entry.getContent().getPayload() != null) {
					if (entry.getContent().getPayload().getField() != null) {
						return true;
					} else {
						LOGGER.log(Level.WARNING, "Validating Entry failed - list of fields not found.");
					}
				} else {
					LOGGER.log(Level.WARNING, "Validating Entry failed - Payload not found.");
				}
			} else {
				LOGGER.log(Level.WARNING, "Validating Entry failed - Content not found.");
			}
		} else {
			LOGGER.log(Level.WARNING, "Validating Entry failed - Entry was null.");
		}

		return false;
	}

	public List getFieldsForEntry(Entry entry) {
		if (checkIfEntryHasContentPayloadAndListOfFields(entry)) {
			return entry.getContent().getPayload().getField();
		}
		return null;
	}

	public String processStructuredTextField(OneArticleBean article, String fieldName) {
		return processAndReplaceOvermatterAndNoteTags(getStructuredText(article, fieldName));
	}

	public boolean imagesPresent(OneArticleBean article) {
		return article != null && (article.getImages() != null && !article.getImages().isEmpty());
	}

	public boolean resourcesPresent(OneArticleBean article) {
		return article != null && (article.getResources() != null && !article.getResources().isEmpty());
	}

	public boolean isTopElement(ContentId contentId, List<EscenicContent> escenicContentList ) {
		/**
		 * This is to prevent pushing the image twice if it's both in body & set as top element as well
         * note - this method marks the image to be both topElement and Inline which allows to only have one
         * instance of that image in the content list that will be used to correctly process top element
         * and parse and process all inline instances
		 */
		if (contentId != null) {
			if (escenicContentList != null) {
				for (EscenicContent escenicContent : escenicContentList) {
					if (escenicContent != null && escenicContent.getOnecmsContentId() != null) {
						if (StringUtils.equalsIgnoreCase(IdUtil.toIdString(escenicContent.getOnecmsContentId()), IdUtil.toIdString(contentId))) {
							//they're the same, therefore update the escenic content with the flag and return and avoid processing
							if (escenicContent instanceof EscenicImage) {
								EscenicImage img = (EscenicImage) escenicContent;

								if (img != null) {
									img.setInlineElement(true);
									return true;
								}
							}

							if (escenicContent instanceof EscenicGallery) {
								EscenicGallery gallery = (EscenicGallery) escenicContent;

								if (gallery != null) {
									gallery.setInlineElement(true);
									return true;
								}
							}

                            if (escenicContent instanceof EscenicContentReference) {
                                EscenicContentReference video = (EscenicContentReference) escenicContent;

                                if (video != null) {
                                    video.setInlineElement(true);
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
		}
		return false;
	}


	public CloseableHttpResponse getImageThumbnailResponse(String url) throws IOException, ErrorResponseException {
		if (StringUtils.isNotBlank(url)) {
			url = URIUtil.encodeQuery(url);
			if (LOGGER.isLoggable(Level.FINEST)) {
				LOGGER.finest("Sending the following query to escenic:\n" + url);
			}
			HttpGet request = new HttpGet(url);
			request.setHeader(generateAuthenticationHeader(escenicConfig.getUsername(), escenicConfig.getPassword()));
			return httpClient.execute(request);
		}
		LOGGER.log(Level.SEVERE, "");
		return null;
	}

	protected ContentManager getContentManager() {
		return this.contentManager;
	}

	protected DamEngagementUtils getEngagementUtils() {
		if (engagementUtils == null) {
			engagementUtils = new DamEngagementUtils(contentManager);
		}
		return engagementUtils;
	}

	protected EscenicConfig getEscenicConfig() {
		return this.escenicConfig;
	}

	protected PolicyCMServer getCmServer()  {
		return this.cmServer;
	}

	public String getEscenicModel(Websection websection, String modelType) {
		return escenicConfig.getModelUrl() + websection.getPublicationName() + modelType;
	}

	protected Entry processExitingContent(Entry existingEntry, Entry entry, boolean isArticle) {
		if (existingEntry != null && entry != null) {

			List<Field> existingFields = getFieldsForEntry(existingEntry);
			List<Field> newFields = getFieldsForEntry(entry);

			existingFields = generateUpdatedListOfFields(existingFields, newFields);
			existingEntry.getContent().getPayload().setField(existingFields);
			existingEntry.setControl(entry.getControl());

			existingEntry.setTitle(entry.getTitle());
			existingEntry.setLink(mergeLinks(existingEntry.getLink(), entry.getLink()));

			//we're resetting the summary to ensure invalid xhtml chars are being escaped
			cleanUpSummary(existingEntry);
			existingEntry.setPublication(cleanUpPublication(existingEntry.getPublication()));

			if (isArticle) {
				existingEntry.setAvailable(entry.getAvailable());
				existingEntry.setExpires(entry.getExpires());
				if (StringUtils.isNotBlank(entry.getPublished())) {
					existingEntry.setUpdated(entry.getPublished());
				} else {
					existingEntry.setUpdated(existingEntry.getPublished());
				}
			}

			return existingEntry;
		}
		return entry;
	}

	public int getResponseStatusCode(CloseableHttpResponse response) throws EscenicResponseException {
		if (response != null && response.getStatusLine() != null) {
			return response.getStatusLine().getStatusCode();
		}
		throw new EscenicResponseException("Failed to process the response from escenic web service.");
	}

}
