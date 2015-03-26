/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.registry.extensions.handlers.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.OMNamespace;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.RegistryConstants;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.ResourceImpl;
import org.wso2.carbon.registry.core.config.RegistryContext;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.jdbc.handlers.RequestContext;
import org.wso2.carbon.registry.core.utils.RegistryUtils;
import org.wso2.carbon.registry.extensions.utils.CommonConstants;

import javax.xml.namespace.QName;
import java.util.*;

/**
 * This class contains static methods to generate REST Service registry artifact from the swagger doc added to the
 * Registry.
 */
public class RESTServiceUtils {

	private static final Log log = LogFactory.getLog(RESTServiceUtils.class);
	private static final String OVERVIEW = "overview";
	private static final String PROVIDER = "provider";
	private static final String NAME = "name";
	private static final String CONTEXT = "context";
	private static final String VERSION = "version";
	private static final String TRANSPORTS = "transports";
	private static final String DESCRIPTION = "description";
	private static final String URI_TEMPLATE = "uritemplate";
	private static final String URL_PATTERN = "urlPattern";
	private static final String AUTH_TYPE = "authType";
	private static final String HTTP_VERB = "httpVerb";

	private static OMFactory factory = OMAbstractFactory.getOMFactory();
	private static OMNamespace namespace = factory.createOMNamespace(CommonConstants.SERVICE_ELEMENT_NAMESPACE, "");
	private static String commonRestServiceLocation;
	private static String commonEndpointLocation;

	/**
	 * Extracts the data from swagger and creates an REST Service registry artifact.
	 *
	 * @param swaggerDocObject      swagger Json Object.
	 * @param swaggerVersion        swagger version.
	 * @param resourceObjects       swagger resource object list.
	 * @return                      The API metadata
	 * @throws RegistryException    If swagger content is invalid.
	 */
	public static OMElement createRestServiceArtifact(JsonObject swaggerDocObject, String swaggerVersion,
	                                                  List<JsonObject> resourceObjects) throws RegistryException {

		OMElement data = factory.createOMElement(CommonConstants.SERVICE_ELEMENT_ROOT, namespace);
		OMElement overview = factory.createOMElement(OVERVIEW, namespace);
		OMElement provider = factory.createOMElement(PROVIDER, namespace);
		OMElement name = factory.createOMElement(NAME, namespace);
		OMElement context = factory.createOMElement(CONTEXT, namespace);
		OMElement apiVersion = factory.createOMElement(VERSION, namespace);
		OMElement transports = factory.createOMElement(TRANSPORTS, namespace);
		OMElement description = factory.createOMElement(DESCRIPTION, namespace);
		List<OMElement> uriTemplates = null;

		JsonObject infoObject = swaggerDocObject.get(SwaggerConstants.INFO).getAsJsonObject();
		//get api name.
		String apiName = getChildElementText(infoObject, SwaggerConstants.TITLE).replaceAll("\\s", "");
		name.setText(apiName);
		context.setText("/" + apiName);
		//get api description.
		description.setText(getChildElementText(infoObject, SwaggerConstants.DESCRIPTION));
		//get api provider. (Current logged in user) : Alternative - CurrentSession.getUser();
		provider.setText(CarbonContext.getThreadLocalCarbonContext().getUsername());

		if (SwaggerConstants.SWAGGER_VERSION_2.equals(swaggerVersion)) {
			apiVersion.setText(getChildElementText(infoObject, SwaggerConstants.VERSION));
			transports.setText(getChildElementText(swaggerDocObject, SwaggerConstants.SCHEMES));
			uriTemplates = createURITemplateFromSwagger2(swaggerDocObject);
		} else if (SwaggerConstants.SWAGGER_VERSION_12.equals(swaggerVersion)) {
			apiVersion.setText(getChildElementText(swaggerDocObject, SwaggerConstants.API_VERSION));
			uriTemplates = createURITemplateFromSwagger12(resourceObjects);
		}

		overview.addChild(provider);
		overview.addChild(name);
		overview.addChild(context);
		overview.addChild(apiVersion);
		overview.addChild(transports);
		overview.addChild(description);
		data.addChild(overview);

		if (uriTemplates != null) {
			for (OMElement uriTemplate : uriTemplates) {
				data.addChild(uriTemplate);
			}
		}

		return data;
	}

	/**
	 * Saves the REST Service registry artifact created from the imported swagger definition.
	 *
	 * @param requestContext        information about current request.
	 * @param data                  service artifact metadata.
	 * @throws RegistryException    If a failure occurs when adding the api to registry.
	 */
	public static String addServiceToRegistry(RequestContext requestContext, OMElement data) throws RegistryException {

		Registry registry = requestContext.getRegistry();
		//Creating new resource.
		Resource serviceResource = new ResourceImpl();
		//setting API media type.
		serviceResource.setMediaType(CommonConstants.REST_SERVICE_MEDIA_TYPE);

		OMElement overview = data.getFirstChildWithName(new QName(CommonConstants.SERVICE_ELEMENT_NAMESPACE, OVERVIEW));
		String serviceVersion =
				overview.getFirstChildWithName(new QName(CommonConstants.SERVICE_ELEMENT_NAMESPACE, VERSION)).getText();
		String apiName =
				overview.getFirstChildWithName(new QName(CommonConstants.SERVICE_ELEMENT_NAMESPACE, NAME)).getText();
		serviceVersion = (serviceVersion == null) ? CommonConstants.SERVICE_VERSION_DEFAULT_VALUE : serviceVersion;

		//set version property.
		serviceResource.setProperty(RegistryConstants.VERSION_PARAMETER_NAME, serviceVersion);
		//set content.
		serviceResource.setContent(RegistryUtils.encodeString(data.toString()));

		String resourceId = serviceResource.getUUID();
		//set resource UUID
		resourceId = (resourceId == null) ? UUID.randomUUID().toString() : resourceId;

		serviceResource.setUUID(resourceId);
		String servicePath = getChrootedServiceLocation(requestContext.getRegistryContext()) +
		                     CarbonContext.getThreadLocalCarbonContext().getUsername() +
		                     RegistryConstants.PATH_SEPARATOR + apiName +
		                     RegistryConstants.PATH_SEPARATOR + serviceVersion +
		                     RegistryConstants.PATH_SEPARATOR + apiName + "-rest_service";
		//saving the api resource to repository.
		registry.put(servicePath, serviceResource);
		log.info("REST Service created at " + servicePath);
		return servicePath;
	}

	public static String addEndpointToRegistry(RequestContext requestContext, OMElement endpointElement, String serviceName)
			throws RegistryException {
		Registry registry = requestContext.getRegistry();
		//Creating new resource.
		Resource endpointResource = new ResourceImpl();
		//setting endpoint media type.
		endpointResource.setMediaType(CommonConstants.ENDPOINT_MEDIA_TYPE);

		OMElement overview = endpointElement.getFirstChildWithName(new QName(CommonConstants.SERVICE_ELEMENT_NAMESPACE, OVERVIEW));
		String endpointVersion =
				overview.getFirstChildWithName(new QName(CommonConstants.SERVICE_ELEMENT_NAMESPACE, VERSION)).getText();
		String endpointName =
				overview.getFirstChildWithName(new QName(CommonConstants.SERVICE_ELEMENT_NAMESPACE, NAME)).getText();
		endpointVersion = (endpointVersion == null) ? CommonConstants.SERVICE_VERSION_DEFAULT_VALUE : endpointVersion;

		//set version property.
		endpointResource.setProperty(RegistryConstants.VERSION_PARAMETER_NAME, endpointVersion);
		//set content.
		endpointResource.setContent(RegistryUtils.encodeString(endpointElement.toString()));

		String resourceId = endpointResource.getUUID();
		//set resource UUID
		resourceId = (resourceId == null) ? UUID.randomUUID().toString() : resourceId;

		endpointResource.setUUID(resourceId);
		String endpointPath = getChrootedEndpointLocation(requestContext.getRegistryContext()) + serviceName +
		                     RegistryConstants.PATH_SEPARATOR + endpointVersion +
		                     RegistryConstants.PATH_SEPARATOR + endpointName;
		//saving the api resource to repository.
		registry.put(endpointPath, endpointResource);
		log.info("Endpoint created at " + endpointPath);
		return endpointPath;
	}

	/**
	 * Returns a Json element as a string
	 *
	 * @param object    json Object
	 * @param key       element key
	 * @return          Element value
	 */
	public static String getChildElementText(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element != null) {
			return object.get(key).getAsString();
		}
		return null;
	}

	/**
	 * Contains the logic to create URITemplate XML Element from the swagger 1.2 resource.
	 *
	 * @param resourceObjects   the path resource documents.
	 * @return                  URITemplate element.
	 */
	private static List<OMElement> createURITemplateFromSwagger12(List<JsonObject> resourceObjects) {

		List<OMElement> uriTemplates = new ArrayList<>();

		for (JsonObject resourceObject : resourceObjects) {
			JsonArray pathResources = resourceObject.getAsJsonArray(SwaggerConstants.APIS);

			//Iterating through the Paths
			for (JsonElement pathResource : pathResources) {
				JsonObject path = pathResource.getAsJsonObject();
				String pathText = path.get(SwaggerConstants.PATH).getAsString();
				JsonArray methods = path.getAsJsonArray(SwaggerConstants.OPERATIONS);

				//Iterating through HTTP methods (Actions)
				for (JsonElement method : methods) {
					JsonObject methodObj = method.getAsJsonObject();

					OMElement uriTemplateElement = factory.createOMElement(URI_TEMPLATE, namespace);
					OMElement urlPatternElement = factory.createOMElement(URL_PATTERN, namespace);
					OMElement httpVerbElement = factory.createOMElement(HTTP_VERB, namespace);
					OMElement authTypeElement = factory.createOMElement(AUTH_TYPE, namespace);

					urlPatternElement.setText(pathText);
					httpVerbElement.setText(methodObj.get(SwaggerConstants.METHOD).getAsString());

					//Adding urlPattern element to URITemplate element.
					uriTemplateElement.addChild(urlPatternElement);
					uriTemplateElement.addChild(httpVerbElement);
					uriTemplateElement.addChild(authTypeElement);
					uriTemplates.add(uriTemplateElement);
				}
			}

		}

		return uriTemplates;
	}

	/**
	 * Contains the logic to create URITemplate XML Element from the swagger 2.0 resource.
	 *
	 * @param swaggerDocObject  swagger document
	 * @return                  URITemplate element.
	 */
	private static List<OMElement> createURITemplateFromSwagger2(JsonObject swaggerDocObject) {

		List<OMElement> uriTemplates = new ArrayList<>();

		JsonObject paths = swaggerDocObject.get(SwaggerConstants.PATHS).getAsJsonObject();
		Set<Map.Entry<String, JsonElement>> pathSet = paths.entrySet();

		for (Map.Entry path : pathSet) {
			JsonObject urlPattern = ((JsonElement) path.getValue()).getAsJsonObject();
			String pathText = path.getKey().toString();
			Set<Map.Entry<String, JsonElement>> operationSet = urlPattern.entrySet();

			for (Map.Entry operationEntry : operationSet) {
				OMElement uriTemplateElement = factory.createOMElement(URI_TEMPLATE, namespace);
				OMElement urlPatternElement = factory.createOMElement(URL_PATTERN, namespace);
				OMElement httpVerbElement = factory.createOMElement(HTTP_VERB, namespace);
				OMElement authTypeElement = factory.createOMElement(AUTH_TYPE, namespace);

				urlPatternElement.setText(pathText);
				httpVerbElement.setText(operationEntry.getKey().toString());

				uriTemplateElement.addChild(urlPatternElement);
				uriTemplateElement.addChild(httpVerbElement);
				uriTemplateElement.addChild(authTypeElement);
				uriTemplates.add(uriTemplateElement);
			}

		}
		return uriTemplates;
	}

	/**
	 * Returns the root location of the API.
	 *
	 * @param registryContext   registry context
	 * @return                  The root location of the API artifact.
	 */
	private static String getChrootedServiceLocation(RegistryContext registryContext) {
		return RegistryUtils.getAbsolutePath(registryContext, RegistryConstants.GOVERNANCE_REGISTRY_BASE_PATH +
		                                                      commonRestServiceLocation);
	}

	/**
	 * Returns the root location of the endpoint.
	 *
	 * @param registryContext   registry context
	 * @return                  The root location of the Endpoint artifact.
	 */
	private static String getChrootedEndpointLocation(RegistryContext registryContext) {
		return RegistryUtils.getAbsolutePath(registryContext, RegistryConstants.GOVERNANCE_REGISTRY_BASE_PATH +
		                                                      commonEndpointLocation);
	}

	/**
	 * Set the restServiceLocation.
	 *
	 * @param restServiceLocation  the restServiceLocation
	 */
	public static void setCommonRestServiceLocation(String restServiceLocation) {
		RESTServiceUtils.commonRestServiceLocation = restServiceLocation;
	}

	/**
	 * Set the endpointLocation.
	 *
	 * @param endpointLocation  the endpointLocation
	 */
	public static void setCommonEndpointLocation(String endpointLocation) {
		RESTServiceUtils.commonEndpointLocation = endpointLocation;
	}
}
