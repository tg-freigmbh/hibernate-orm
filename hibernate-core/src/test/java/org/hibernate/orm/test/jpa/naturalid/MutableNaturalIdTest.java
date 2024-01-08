/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.orm.test.jpa.naturalid;

import org.hibernate.community.dialect.AltibaseDialect;
import org.hibernate.dialect.AbstractHANADialect;
import org.hibernate.dialect.OracleDialect;

import org.hibernate.testing.TestForIssue;
import org.hibernate.testing.orm.junit.SkipForDialect;
import org.hibernate.orm.test.jpa.model.AbstractJPATest;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Steve Ebersole
 */
@SkipForDialect(dialectClass = OracleDialect.class, matchSubTypes = true,
		reason = "Oracle do not support identity key generation")
@SkipForDialect(dialectClass = AbstractHANADialect.class, matchSubTypes = true,
		reason = "Hana do not support identity key generation")
@SkipForDialect(dialectClass = AltibaseDialect.class,
		reason = "Altibase do not support identity key generation")
public class MutableNaturalIdTest extends AbstractJPATest {
	@Override
	protected Class<?>[] getAnnotatedClasses() {
		return new Class[] { Group.class, ClassWithIdentityColumn.class };
	}

	@Test
	public void testSimpleNaturalIdLoadAccessCacheWithUpdate() {
		inTransaction(
				session -> {
					Group g = new Group( 1, "admin" );
					session.persist( g );
				}
		);

		inTransaction(
				session -> {
					Group g = session.bySimpleNaturalId( Group.class ).load( "admin" );
					assertNotNull( g );
					Group g2 = (Group) session.bySimpleNaturalId( Group.class ).getReference( "admin" );
					assertTrue( g == g2 );
					g.setName( "admins" );
					session.flush();
					g2 = session.bySimpleNaturalId( Group.class ).getReference( "admins" );
					assertTrue( g == g2 );
				}
		);

		inTransaction(
				session ->
						session.createQuery( "delete Group" ).executeUpdate()
		);
	}

	@Test
	@TestForIssue(jiraKey = "HHH-7304")
	public void testInLineSynchWithIdentityColumn() {
		inTransaction(
				session -> {
					ClassWithIdentityColumn e = new ClassWithIdentityColumn();
					e.setName( "Dampf" );
					session.save( e );
					e.setName( "Klein" );
					final var klein = session.bySimpleNaturalId(ClassWithIdentityColumn.class).load("Klein");
					final var enableNaturalIdCache = sessionFactory().getSessionFactoryOptions().isEnableNaturalIdCache();
					if (enableNaturalIdCache) {
						assertNotNull(klein);
					} else {
						assertNull(klein);
					}
				}
		);
	}

}
