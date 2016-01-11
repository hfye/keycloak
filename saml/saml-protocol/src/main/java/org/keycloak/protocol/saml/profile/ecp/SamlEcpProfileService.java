package org.keycloak.protocol.saml.profile.ecp;

import org.keycloak.dom.saml.v2.protocol.AuthnRequestType;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.utils.DefaultAuthenticationFlows;
import org.keycloak.protocol.saml.JaxrsSAML2BindingBuilder;
import org.keycloak.protocol.saml.SamlConfigAttributes;
import org.keycloak.protocol.saml.SamlProtocol;
import org.keycloak.protocol.saml.SamlService;
import org.keycloak.protocol.saml.profile.ecp.util.Soap;
import org.keycloak.saml.SAML2LogoutResponseBuilder;
import org.keycloak.saml.common.constants.JBossSAMLConstants;
import org.keycloak.saml.common.constants.JBossSAMLURIConstants;
import org.keycloak.saml.common.exceptions.ConfigurationException;
import org.keycloak.saml.common.exceptions.ProcessingException;
import org.keycloak.services.managers.AuthenticationManager;
import org.w3c.dom.Document;

import javax.ws.rs.core.Response;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPHeaderElement;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
public class SamlEcpProfileService extends SamlService {

    private static final String NS_PREFIX_PROFILE_ECP = "ecp";
    private static final String NS_PREFIX_SAML_PROTOCOL = "samlp";
    private static final String NS_PREFIX_SAML_ASSERTION = "saml";

    public SamlEcpProfileService(RealmModel realm, EventBuilder event, AuthenticationManager authManager) {
        super(realm, event, authManager);
    }

    public Response authenticate(InputStream inputStream) {
        try {
            return new PostBindingProtocol() {
                @Override
                protected String getBindingType(AuthnRequestType requestAbstractType) {
                    return SamlProtocol.SAML_SOAP_BINDING;
                }

                @Override
                protected Response loginRequest(String relayState, AuthnRequestType requestAbstractType, ClientModel client) {
                    // force passive authentication when executing this profile
                    requestAbstractType.setIsPassive(true);
                    requestAbstractType.setDestination(uriInfo.getAbsolutePath());
                    return super.loginRequest(relayState, requestAbstractType, client);
                }
            }.execute(Soap.toSamlHttpPostMessage(inputStream), null, null);
        } catch (Exception e) {
            String reason = "Some error occurred while processing the AuthnRequest.";
            String detail = e.getMessage();

            if (detail == null) {
                detail = reason;
            }

            return Soap.createFault().reason(reason).detail(detail).build();
        }
    }

    @Override
    protected Response newBrowserAuthentication(ClientSessionModel clientSession, boolean isPassive, SamlProtocol samlProtocol) {
        return super.newBrowserAuthentication(clientSession, isPassive, createEcpSamlProtocol());
    }

    private SamlProtocol createEcpSamlProtocol() {
        return new SamlProtocol() {
            // method created to send a SOAP Binding response instead of a HTTP POST response
            @Override
            protected Response buildAuthenticatedResponse(ClientSessionModel clientSession, String redirectUri, Document samlDocument, JaxrsSAML2BindingBuilder bindingBuilder) throws ConfigurationException, ProcessingException, IOException {
                Document document = bindingBuilder.postBinding(samlDocument).getDocument();

                try {
                    Soap.SoapMessageBuilder messageBuilder = Soap.createMessage()
                            .addNamespace(NS_PREFIX_SAML_ASSERTION, JBossSAMLURIConstants.ASSERTION_NSURI.get())
                            .addNamespace(NS_PREFIX_SAML_PROTOCOL, JBossSAMLURIConstants.PROTOCOL_NSURI.get())
                            .addNamespace(NS_PREFIX_PROFILE_ECP, JBossSAMLURIConstants.ECP_PROFILE.get());

                    createEcpResponseHeader(redirectUri, messageBuilder);
                    createRequestAuthenticatedHeader(clientSession, messageBuilder);

                    messageBuilder.addToBody(document);

                    return messageBuilder.build();
                } catch (Exception e) {
                    throw new RuntimeException("Error while creating SAML response.", e);
                }
            }

            private void createRequestAuthenticatedHeader(ClientSessionModel clientSession, Soap.SoapMessageBuilder messageBuilder) {
                ClientModel client = clientSession.getClient();

                if ("true".equals(client.getAttribute(SamlConfigAttributes.SAML_CLIENT_SIGNATURE_ATTRIBUTE))) {
                    SOAPHeaderElement ecpRequestAuthenticated = messageBuilder.addHeader(JBossSAMLConstants.REQUEST_AUTHENTICATED.get(), NS_PREFIX_PROFILE_ECP);

                    ecpRequestAuthenticated.setMustUnderstand(true);
                    ecpRequestAuthenticated.setActor("http://schemas.xmlsoap.org/soap/actor/next");
                }
            }

            private void createEcpResponseHeader(String redirectUri, Soap.SoapMessageBuilder messageBuilder) throws SOAPException {
                SOAPHeaderElement ecpResponseHeader = messageBuilder.addHeader(JBossSAMLConstants.RESPONSE.get(), NS_PREFIX_PROFILE_ECP);

                ecpResponseHeader.setMustUnderstand(true);
                ecpResponseHeader.setActor("http://schemas.xmlsoap.org/soap/actor/next");
                ecpResponseHeader.addAttribute(messageBuilder.createName(JBossSAMLConstants.ASSERTION_CONSUMER_SERVICE_URL.get()), redirectUri);
            }

            @Override
            protected Response buildErrorResponse(ClientSessionModel clientSession, JaxrsSAML2BindingBuilder binding, Document document) throws ConfigurationException, ProcessingException, IOException {
                return Soap.createMessage().addToBody(document).build();
            }

            @Override
            protected Response buildLogoutResponse(UserSessionModel userSession, String logoutBindingUri, SAML2LogoutResponseBuilder builder, JaxrsSAML2BindingBuilder binding) throws ConfigurationException, ProcessingException, IOException {
                return Soap.createFault().reason("Logout not supported.").build();
            }
        }.setEventBuilder(event).setHttpHeaders(headers).setRealm(realm).setSession(session).setUriInfo(uriInfo);
    }

    @Override
    protected AuthenticationFlowModel getAuthenticationFlow() {
        for (AuthenticationFlowModel flowModel : realm.getAuthenticationFlows()) {
            if (flowModel.getAlias().equals(DefaultAuthenticationFlows.SAML_ECP_FLOW)) {
                return flowModel;
            }
        }

        throw new RuntimeException("Could not resolve authentication flow for SAML ECP Profile.");
    }
}