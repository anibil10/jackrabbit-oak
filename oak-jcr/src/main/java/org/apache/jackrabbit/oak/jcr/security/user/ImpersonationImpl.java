/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.jcr.security.user;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.principal.PrincipalIterator;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Impersonation;
import org.apache.jackrabbit.oak.jcr.security.principal.PrincipalIteratorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.security.auth.Subject;
import java.security.Principal;
import java.security.acl.Group;
import java.util.HashSet;
import java.util.Set;

/**
 * ImpersonationImpl...
 */
class ImpersonationImpl implements Impersonation {

    /**
     * logger instance
     */
    private static final Logger log = LoggerFactory.getLogger(ImpersonationImpl.class);

    private static final String P_IMPERSONATORS = "rep:impersonators";

    private final UserImpl user;

    ImpersonationImpl(UserImpl user) {
        this.user = user;
    }

    //------------------------------------------------------< Impersonation >---
    /**
     * @see org.apache.jackrabbit.api.security.user.Impersonation#getImpersonators()
     */
    @Override
    public PrincipalIterator getImpersonators() throws RepositoryException {
        Set<String> impersonators = getImpersonatorNames();
        if (impersonators.isEmpty()) {
            return PrincipalIteratorAdapter.EMPTY;
        } else {
            final PrincipalManager pMgr = getPrincipalManager();
            Set<Principal> s = new HashSet<Principal>();
            for (final String pName : impersonators) {
                Principal p = pMgr.getPrincipal(pName);
                if (p == null) {
                    log.debug("Impersonator " + pName + " does not correspond to a known Principal.");
                    p = new Principal() {
                        @Override
                        public String getName() {
                            return pName;
                        }
                    };
                }
                s.add(p);

            }
            return new PrincipalIteratorAdapter(s);
        }
    }

    /**
     * @see org.apache.jackrabbit.api.security.user.Impersonation#grantImpersonation(Principal)
     */
    @Override
    public synchronized boolean grantImpersonation(Principal principal) throws RepositoryException {
        String principalName = principal.getName();
        PrincipalManager pMgr = getPrincipalManager();
        if (!pMgr.hasPrincipal(principalName)) {
            log.debug("Cannot grant impersonation to an unknown principal.");
            return false;
        }

        Principal p = pMgr.getPrincipal(principalName);
        if (p instanceof Group) {
            log.debug("Cannot grant impersonation to a principal that is a Group.");
            return false;
        }

        // make sure user does not impersonate himself
        if (user.getPrincipal().getName().equals(principalName)) {
            log.warn("Cannot grant impersonation to oneself.");
            return false;
        }

        // make sure the given principal doesn't refer to the admin user.
        Authorizable a = user.getUserManager().getAuthorizable(p);
        if (a != null && user.getUserManager().isAdminId(a.getID())) {
            log.debug("Admin principal is already granted impersonation.");
            return false;
        }

        boolean granted = false;
        Set<String> impersonators = getImpersonatorNames();
        if (impersonators.add(principalName)) {
            updateImpersonatorNames(impersonators);
            granted = true;
        }
        return granted;
    }

    /**
     * @see Impersonation#revokeImpersonation(java.security.Principal)
     */
    @Override
    public synchronized boolean revokeImpersonation(Principal principal) throws RepositoryException {
        boolean revoked = false;
        String pName = principal.getName();

        Set<String> impersonators = getImpersonatorNames();
        if (impersonators.remove(pName)) {
            updateImpersonatorNames(impersonators);
            revoked = true;
        }
        return revoked;
    }

    /**
     * @see Impersonation#allows(javax.security.auth.Subject)
     */
    @Override
    public boolean allows(Subject subject) throws RepositoryException {
        if (subject == null) {
            return false;
        }

        Set<String> principalNames = new HashSet<String>();
        for (Principal p : subject.getPrincipals()) {
            principalNames.add(p.getName());
        }

        boolean allows;
        Set<String> impersonators = getImpersonatorNames();
        allows = impersonators.removeAll(principalNames);

        if (!allows) {
            // check if subject belongs to administrator user
            for (Principal p : subject.getPrincipals()) {
                if (p instanceof Group) {
                    continue;
                }
                UserManagerImpl userManager = getUserManagerImpl();
                Authorizable a = userManager.getAuthorizable(p);
                if (a != null && userManager.isAdminId(a.getID())) {
                    allows = true;
                    break;
                }
            }
        }
        return allows;
    }

    //------------------------------------------------------------< private >---

    private Set<String> getImpersonatorNames() throws RepositoryException {
        Set<String> princNames = new HashSet<String>();
        if (user.getNode().hasProperty(P_IMPERSONATORS)) {
            Value[] vs = user.getNode().getProperty(P_IMPERSONATORS).getValues();
            for (Value v : vs) {
                princNames.add(v.getString());
            }
        }
        return princNames;
    }

    private void updateImpersonatorNames(Set<String> principalNames) throws RepositoryException {
        String[] pNames = principalNames.toArray(new String[principalNames.size()]);
        if (pNames.length == 0) {
            user.getUserManager().removeInternalProperty(user.getNode(), P_IMPERSONATORS);
        } else {
            user.getUserManager().setInternalProperty(user.getNode(), P_IMPERSONATORS, pNames, PropertyType.STRING);
        }
    }

    private PrincipalManager getPrincipalManager() throws RepositoryException {
        Session s = user.getNode().getSession();
        if (s instanceof JackrabbitSession) {
            return ((JackrabbitSession) s).getPrincipalManager();
        } else {
            throw new UnsupportedRepositoryOperationException("Principal management not supported.");
        }
    }

    private UserManagerImpl getUserManagerImpl() {
        return user.getUserManager();
    }
}