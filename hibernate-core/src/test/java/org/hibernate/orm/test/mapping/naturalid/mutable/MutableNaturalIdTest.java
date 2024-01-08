/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.orm.test.mapping.naturalid.mutable;

import java.lang.reflect.Field;

import org.hibernate.HibernateException;
import org.hibernate.LockOptions;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.stat.spi.StatisticsImplementor;
import org.hibernate.tuple.entity.EntityMetamodel;

import org.hibernate.testing.TestForIssue;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hibernate.cfg.AvailableSettings.GENERATE_STATISTICS;
import static org.hibernate.cfg.AvailableSettings.USE_QUERY_CACHE;
import static org.hibernate.cfg.AvailableSettings.USE_SECOND_LEVEL_CACHE;
import static org.junit.Assert.*;

/**
 * @author Gavin King
 */
@ServiceRegistry(
		settings = {
				@Setting( name = USE_SECOND_LEVEL_CACHE, value = "true" ),
				@Setting( name = USE_QUERY_CACHE, value = "true" ),
				@Setting( name = GENERATE_STATISTICS, value = "true" ),
		}
)
@DomainModel( xmlMappings = "org/hibernate/orm/test/mapping/naturalid/mutable/User.hbm.xml" )
@SessionFactory
public class MutableNaturalIdTest {

	@Test
	@TestForIssue( jiraKey = "HHH-10360")
	public void testNaturalIdNullability(SessionFactoryScope scope) {
		final SessionFactoryImplementor sessionFactory = scope.getSessionFactory();
		final EntityMappingType entityMappingType = sessionFactory.getRuntimeMetamodels().getEntityMappingType( User.class );
		final EntityPersister persister = entityMappingType.getEntityPersister();
		final EntityMetamodel entityMetamodel = persister.getEntityMetamodel();

		// nullability is not specified, so it should be non-nullable by hbm-specific default
		assertFalse( persister.getPropertyNullability()[entityMetamodel.getPropertyIndex( "name" )] );
		assertFalse( persister.getPropertyNullability()[entityMetamodel.getPropertyIndex( "org" )] );
	}

	@AfterEach
	public void dropTestData(SessionFactoryScope scope) {
		scope.inTransaction( (session) -> session.createQuery( "delete User" ).executeUpdate() );
	}

	@Test
	public void testCacheSynchronizationOnMutation(SessionFactoryScope scope) {
		final Long id = scope.fromTransaction(
				(session) -> {
					final User user = new User( "gavin", "hb", "secret" );
					session.persist( user );
					return user.getId();
				}
		);

		scope.inTransaction(
				(session) -> {
					final User user = session.byId( User.class ).getReference( id );
					user.setOrg( "ceylon" );

					final User original = session.byNaturalId( User.class )
							.using( "name", "gavin" )
							.using( "org", "hb" )
							.load();
					//if there is a cache, hibernate gives you changes that havent been flushed to the db,
					// otherwise it gives you the db state
					if (scope.getSessionFactory().getSessionFactoryOptions().isEnableNaturalIdCache()) {
						assertNull( original );
						assertNotSame( user, original );
					} else {
						assertSame( user, original );
					}
				}
		);
	}

	@Test
	public void testReattachmentNaturalIdCheck(SessionFactoryScope scope) throws Throwable {
		final User created = scope.fromTransaction(
				(session) -> {
					final User user = new User( "gavin", "hb", "secret" );
					session.persist( user );
					return user;
				}
		);

		final Field name = User.class.getDeclaredField( "name" );
		name.setAccessible( true );
		name.set( created, "Gavin" );

		scope.inTransaction(
				(session) -> {
					try {
						session.update( created );
						final User loaded = session
								.byNaturalId( User.class )
								.using( "name", "Gavin" )
								.using( "org", "hb" )
								.load();
						//if there is a cache, hibernate gives you changes that havent been flushed to the db,
						// otherwise it gives you the db state
						if (scope.getSessionFactory().getSessionFactoryOptions().isEnableNaturalIdCache()) {
							assertNotNull(loaded);
						} else {
							assertNull(loaded);
						}
					}
					catch( HibernateException expected ) {
						session.getTransaction().markRollbackOnly();
					}
					catch( Throwable t ) {
						try {
							session.getTransaction().markRollbackOnly();
						}
						catch ( Throwable ignore ) {
						}
						throw t;
					}
				}
		);
	}
	
	
	@Test
	public void testReattachmentUnmodifiedNaturalIdCheck(SessionFactoryScope scope) throws Throwable {
		final User created = scope.fromTransaction(
				(session) -> {
					final User user = new User( "gavin", "hb", "secret" );
					session.persist( user );
					return user;
				}
		);

		final Field name = User.class.getDeclaredField( "name" );
		name.setAccessible( true );

		scope.inTransaction(
				(session) -> {
					try {
						session.buildLockRequest( LockOptions.NONE ).lock( created );

						name.set( created, "Gavin" );
						final User loaded = session
								.byNaturalId( User.class )
								.using( "name", "Gavin" )
								.using( "org", "hb" )
								.load();
						//if there is a cache, hibernate gives you changes that havent been flushed to the db,
						// otherwise it gives you the db state
						if (scope.getSessionFactory().getSessionFactoryOptions().isEnableNaturalIdCache()) {
							assertNotNull(loaded);
						} else {
							assertNull(loaded);
							assertNotNull(session
									.byNaturalId(User.class)
									.using("name", "gavin")
									.using("org", "hb")
									.load());

						}

					}
					catch (Throwable t) {
						try {
							session.getTransaction().markRollbackOnly();
						}
						catch (Throwable ignore) {
							// ignore
						}
						if ( t instanceof AssertionError ) {
							throw (AssertionError) t;
						}
					}
				} );
	}	

	@Test
	public void testNonexistentNaturalIdCache(SessionFactoryScope scope) {
		final StatisticsImplementor statistics = scope.getSessionFactory().getStatistics();
		statistics.clear();

		scope.inTransaction(
				(session) -> {
					Object nullUser = session.byNaturalId( User.class )
							.using( "name", "gavin" )
							.using( "org", "hb" )
							.load();
					assertNull( nullUser );
				}
		);

		assertEquals( 0, statistics.getNaturalIdCacheHitCount(), 0 );
		assertEquals( 0, statistics.getNaturalIdCachePutCount(), 0 );

		scope.inTransaction(
				(session) -> session.persist( new User("gavin", "hb", "secret") )
		);

		statistics.clear();

		scope.inTransaction(
				(session) -> {
					final User user = session.byNaturalId( User.class )
							.using( "name", "gavin" )
							.using( "org", "hb" )
							.load();

					assertNotNull( user );
				}
		);

		assertEquals( 0, statistics.getNaturalIdCacheHitCount() );
		assertEquals( 0, statistics.getNaturalIdCachePutCount() );
	}

	@Test
	public void testNaturalIdCache(SessionFactoryScope scope) {
		scope.inTransaction(
				(session) -> session.persist( new User("gavin", "hb", "secret") )
		);

		scope.inTransaction(
				(session) -> {
					final User user = session.byNaturalId( User.class )
							.using( "name", "gavin" )
							.using( "org", "hb" )
							.load();
					assertNotNull( user );
				}
		);
	}

	@Test
	public void testNaturalIdDeleteUsingCache(SessionFactoryScope scope) {
		scope.inTransaction(
				(session) -> session.persist( new User( "steve", "hb", "superSecret" ) )
		);

		scope.inTransaction(
				(session) -> {
					final User user = session.byNaturalId( User.class )
							.using( "name", "steve" )
							.using( "org", "hb" )
							.load();
					assertNotNull( user );
				}
		);

		scope.inTransaction(
				(session) -> {
					final User user = session.bySimpleNaturalId( User.class )
							.load( new Object[] { "steve", "hb" } );
					assertNotNull( user );
					session.delete( user );
				}
		);

		scope.inTransaction(
				(session) -> {
					final User user = session.byNaturalId( User.class )
							.using( "name", "steve" )
							.using( "org", "hb" )
							.load();
					assertNull( user );

					final User user2 = session.bySimpleNaturalId( User.class )
							.load( new Object[] { "steve", "hb" } );
					assertNull( user2 );
				}
		);
	}

	@Test
	public void testNaturalIdRecreateUsingCache(SessionFactoryScope scope) {
		scope.inTransaction(
				(session) -> session.persist( new User( "steve", "hb", "superSecret" ) )
		);

		scope.inTransaction(
				(session) -> {
					final User user = session.byNaturalId( User.class )
							.using( "name", "steve" )
							.using( "org", "hb" )
							.load();
					assertNotNull( user );

					session.delete( user );
				}
		);
	}

	@Test
	public void testQuerying(SessionFactoryScope scope) {
		scope.inTransaction(
				(session) -> session.persist( new User( "steve", "hb", "superSecret" ) )
		);

		scope.inTransaction(
				(session) -> {
					final User user = (User) session.createQuery( "from User u where u.name = :name" )
							.setParameter( "name", "steve" ).uniqueResult();
					assertNotNull( user );
					assertEquals( "steve", user.getName() );
				}
		);
	}

	@Test
	public void testClear(SessionFactoryScope scope) {
		scope.inTransaction(
				(session) -> session.persist( new User( "steve", "hb", "superSecret" ) )
		);

		final StatisticsImplementor statistics = scope.getSessionFactory().getStatistics();
		statistics.clear();

		scope.inTransaction(
				(session) -> {

					final User beforeClear = session.byNaturalId( User.class )
							.using( "name", "steve" )
							.using( "org", "hb" )
							.load();
					assertNotNull( beforeClear );
					assertEquals( statistics.getPrepareStatementCount(), 1 );

					session.clear();

					final User afterClear = session.byNaturalId( User.class )
							.using( "name", "steve" )
							.using( "org", "hb" )
							.load();
					assertNotNull( afterClear );
					assertEquals( statistics.getPrepareStatementCount(), 2 );

					assertNotSame( beforeClear, afterClear );
				}
		);
	}

	@Test
	public void testEviction(SessionFactoryScope scope) {
		scope.inTransaction(
				(session) -> session.persist( new User( "steve", "hb", "superSecret" ) )
		);

		final StatisticsImplementor statistics = scope.getSessionFactory().getStatistics();
		statistics.clear();

		scope.inTransaction(
				(session) -> {
					final User beforeEvict = session.byNaturalId( User.class )
							.using( "name", "steve" )
							.using( "org", "hb" )
							.load();
					assertNotNull( beforeEvict );
					assertEquals( statistics.getPrepareStatementCount(), 1 );

					session.evict( beforeEvict );

					final User afterEvict = session.byNaturalId( User.class )
							.using( "name", "steve" )
							.using( "org", "hb" )
							.load();
					assertNotNull( afterEvict );
					assertEquals( statistics.getPrepareStatementCount(), 2 );

					assertNotSame( beforeEvict, afterEvict );
				}
		);
	}
}
