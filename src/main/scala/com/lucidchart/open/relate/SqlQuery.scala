package com.lucidchart.open.relate

import java.sql.{Connection, PreparedStatement}
import scala.collection.generic.CanBuildFrom
import scala.collection.mutable

/** A query object that can be expanded */
private[relate] case class ExpandableQuery(
  query: String,
  listParams: mutable.Map[String, ListParam] = mutable.Map[String, ListParam]()
) extends Sql with Expandable {

  val params = Nil
  val queryParams = QueryParams(
    query,
    params,
    listParams
  )

  /** 
   * The copy method used by the Sql Trait
   * Returns a SqlQuery object so that expansion can only occur before the 'on' method
   */
  protected def getCopy(p: List[SqlStatement => Unit]): SqlQuery = SqlQuery(query, p, listParams)
}


/** A query object that has not had parameter values substituted in */
private[relate] case class SqlQuery(
  query: String,
  params: List[SqlStatement => Unit] = Nil,
  listParams: mutable.Map[String, ListParam] = mutable.Map[String, ListParam]()
) extends Sql {

  val queryParams = QueryParams(
    query,
    params,
    listParams
  )

  /**
   * Copy method for the Sql trait
   */
  protected def getCopy(p: List[SqlStatement => Unit]): SqlQuery = copy(params = p)

}

/** The base class for all list type parameters. Contains a name and a count (total number of 
inserted params) */
private[relate] sealed trait ListParam {
  val name: String
  val count: Int
  val charCount: Int
}

/** ListParam type that represents a comma separated list of parameters */
private[relate] case class CommaSeparated(
  name: String,
  count: Int,
  charCount: Int
) extends ListParam

/** ListParam type that represents a comma separated list of tuples */
private[relate] case class Tupled(
  name: String,
  count: Int,
  charCount: Int,
  params: Map[String, Int],
  numTuples: Int,
  tupleSize: Int
) extends ListParam

/** 
 * Expandable is a trait for SQL queries that can be expanded.
 *
 * It defines two expansion methods:
 *  - [[com.lucidchart.open.relate.Expandable#commaSeparated commaSeparated]] for expanding a parameter into a comma separated list
 *  - [[com.lucidchart.open.relate.Expandable#tupled tupled]] for expanding a parameter into a comma separated list of tuples for insertion queries
 *
 * These methods should be called in the [[com.lucidchart.open.relate.Expandable#expand expand]] method.
 * {{{
 * import com.lucidchart.open.relate._
 * import com.lucidchart.open.relate.Query._
 * 
 * val ids = Array(1L, 2L, 3L)  
 *
 * SQL("""
 *   SELECT *
 *   FROM users
 *   WHERE id IN ({ids})
 * """).expand { implicit query =>
 *   commaSeparated("ids", ids.size)
 * }.on {
 *   longs("ids", ids) 
 * }
 * }}}
 */
sealed trait Expandable extends Sql {

  /** The names of list params mapped to their size */
  val listParams: mutable.Map[String, ListParam]

  /**
   * Expand out the query by turning an TraversableOnce into several parameters
   * @param a function that will operate by calling functions on this Expandable instance
   * @return a copy of this Expandable with the query expanded
   */
  def expand(f: Expandable => Unit): Expandable = {
    f(this)
    this
  }

  /**
   * Replace the provided identifier with a comma separated list of parameters
   * WARNING: modifies this Expandable in place
   * @param name the identifier for the parameter
   * @param count the count of parameters in the list
   */
  def commaSeparated(name: String, count: Int) {
    listParams(name) = CommaSeparated(name, count, count * 2)
  }

  def commas(name: String, count: Int): Expandable = {
    expand(_.commaSeparated(name, count))
    this
  }

  /**
   * Replace the provided identifier with a comma separated list of tuples
   * WARNING: modifies this Expandable in place
   * @param name the identifier for the tuples
   * @param columns a list of the column names in the order they should be inserted into
   * the tuples
   * @param count the number of tuples to insert
   */
  def tupled(name: String, columns: Seq[String], count: Int) {
    val namesToIndexes = columns.zipWithIndex.toMap
    listParams(name) = Tupled(
      name,
      count * columns.size,
      count * 3 + count * columns.size * 2,
      namesToIndexes,
      count,
      columns.size
    )
  }

}

private[relate] case class QueryParams(
  query: String,
  params: List[SqlStatement => Unit],
  listParams: mutable.Map[String, ListParam]
)

/** A trait for queries */
sealed trait Sql {

  val query: String
  val params: List[SqlStatement => Unit]
  val listParams: mutable.Map[String, ListParam]
  val queryParams: QueryParams

  /**
   * Classes that inherit the Sql trait will have to implement a method to copy
   * themselves given just a different set of parameters. HINT: Use a case class!
   */
  protected def getCopy(params: List[SqlStatement => Unit]): Sql

  /**
   * Put in values for parameters in the query
   * @param f a function that takes a SqlStatement and sets parameter values using its methods
   * @return a copy of this Sql with the new params
   */
  def on(f: SqlStatement => Unit): Sql = {
    getCopy(f +: params)
  }

  /**
   * Put in values for tuple parameters in the query
   * @param name the tuple identifier in the query
   * @param tuples the objects to loop over and use to insert data into the query
   * @param f a function that takes a TupleStatement and sets parameter values using its methods
   * @return a copy of this Sql with the new tuple params
   */
  def onTuples[A](name: String, tuples: TraversableOnce[A])(f: (A, TupleStatement) => Unit): Sql = {
    val callback: SqlStatement => Unit = { statement =>
      val iterator1 = statement.names(name).toIterator
      val tupleData = statement.listParams(name).asInstanceOf[Tupled]

      while(iterator1.hasNext) {
        var i = iterator1.next
        val iterator2 = tuples.toIterator
        while(iterator2.hasNext) {
          f(iterator2.next, TupleStatement(statement.stmt, tupleData.params, i))
          i += tupleData.tupleSize
        }
      }
    }
    getCopy(callback +: params)
  }

  /**
   * Execute a statement
   * @return whether the query succeeded in its execution
   */
  def execute()(implicit connection: Connection): Boolean = {
    NormalStatementPreparer(queryParams, connection).execute()
  }

  /**
   * Execute an update
   * @return the number of rows update by the query
   */
  def executeUpdate()(implicit connection: Connection): Int = {
    NormalStatementPreparer(queryParams, connection).executeUpdate()
  }

  def executeInsertInt()(implicit connection: Connection): Int = InsertionStatementPreparer(queryParams, connection).execute(_.asSingle(RowParser.insertInt))
  def executeInsertInts()(implicit connection: Connection): List[Int] = InsertionStatementPreparer(queryParams, connection).execute(_.asList(RowParser.insertInt))
  def executeInsertLong()(implicit connection: Connection): Long = InsertionStatementPreparer(queryParams, connection).execute(_.asSingle(RowParser.insertLong))
  def executeInsertLongs()(implicit connection: Connection): List[Long] = InsertionStatementPreparer(queryParams, connection).execute(_.asList(RowParser.insertLong))

  def executeInsertSingle[U](parser: RowParser[U])(implicit connection: Connection): U = InsertionStatementPreparer(queryParams, connection).execute(_.asSingle(parser))
  def executeInsertCollection[U, T[_]](parser: RowParser[U])(implicit cbf: CanBuildFrom[T[U], U, T[U]], connection: Connection): T[U] = InsertionStatementPreparer(queryParams, connection).execute(_.asCollection(parser))

  def asSingle[A](parser: RowParser[A])(implicit connection: Connection): A = NormalStatementPreparer(queryParams, connection).execute(_.asSingle(parser))
  def asSingleOption[A](parser: RowParser[A])(implicit connection: Connection): Option[A] = NormalStatementPreparer(queryParams, connection).execute(_.asSingleOption(parser))
  def asSet[A](parser: RowParser[A])(implicit connection: Connection): Set[A] = NormalStatementPreparer(queryParams, connection).execute(_.asSet(parser))
  def asSeq[A](parser: RowParser[A])(implicit connection: Connection): Seq[A] = NormalStatementPreparer(queryParams, connection).execute(_.asSeq(parser))
  def asIterable[A](parser: RowParser[A])(implicit connection: Connection): Iterable[A] = NormalStatementPreparer(queryParams, connection).execute(_.asIterable(parser))
  def asList[A](parser: RowParser[A])(implicit connection: Connection): List[A] = NormalStatementPreparer(queryParams, connection).execute(_.asList(parser))
  def asMap[U, V](parser: RowParser[(U, V)])(implicit connection: Connection): Map[U, V] = NormalStatementPreparer(queryParams, connection).execute(_.asMap(parser))
  def asScalar[A]()(implicit connection: Connection): A = NormalStatementPreparer(queryParams, connection).execute(_.asScalar[A]())
  def asScalarOption[A]()(implicit connection: Connection): Option[A] = NormalStatementPreparer(queryParams, connection).execute(_.asScalarOption[A]())
  def asCollection[U, T[_]](parser: RowParser[U])(implicit cbf: CanBuildFrom[T[U], U, T[U]], connection: Connection): T[U] = NormalStatementPreparer(queryParams, connection).execute(_.asCollection(parser))
  def asPairCollection[U, V, T[_, _]](parser: RowParser[(U, V)])(implicit cbf: CanBuildFrom[T[U, V], (U, V), T[U, V]], connection: Connection): T[U, V] = NormalStatementPreparer(queryParams, connection).execute(_.asPairCollection(parser))
  
  /**
   * The asIterator method returns an Iterator that will stream data out of the database.
   * This avoids an OutOfMemoryError when dealing with large datasets. Bear in mind that many
   * JDBC implementations will not allow additional queries to the connection before all records
   * in the Iterator have been retrieved.
   * @param parser the RowParser to parse rows with
   * @param fetchSize the number of rows to fetch at a time, defaults to 100. If the JDBC Driver
   * is MySQL, the fetchSize will always default to Int.MinValue, as MySQL's JDBC implementation
   * ignores all other fetchSize values and only streams if fetchSize is Int.MinValue
   */
  def asIterator[A](parser: RowParser[A], fetchSize: Int = 100)(implicit connection: Connection): Iterator[A] = {
    val prepared = StreamedStatementPreparer(queryParams, connection, fetchSize)
    prepared.execute(RowIterator(parser, prepared.stmt, _))
  }
}
