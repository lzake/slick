package scala.slick.driver

import scala.slick.SLICKException
import scala.slick.ql._
import scala.slick.ast._
import scala.slick.util.ValueLinearizer

/**
 * SLICK driver for Derby/JavaDB.
 *
 * <p>This driver implements the ExtendedProfile with the following
 * limitations:</p>
 * <ul>
 *   <li><code>Functions.database</code> is not available in Derby. SLICK
 *     will return an empty string instead.</li>
 *   <li><code>Sequence.curr</code> to get the current value of a sequence is
 *     not supported by Derby. Trying to generate SQL code which uses this
 *     feature throws a SLICKException.</li>
 *   <li>Sequence cycling is supported but does not conform to SQL:2008
 *     semantics. Derby cycles back to the START value instead of MINVALUE or
 *     MAXVALUE.</li>
 * </ul>
 *
 * @author szeiger
 */
class DerbyDriver extends ExtendedDriver { driver =>

  override val typeMapperDelegates = new TypeMapperDelegates
  override def createQueryBuilder(node: Node, vl: ValueLinearizer[_]): QueryBuilder = new QueryBuilder(node, vl)
  override def buildTableDDL(table: AbstractBasicTable[_]): DDL = new DDLBuilder(table).buildDDL
  override def buildSequenceDDL(seq: Sequence[_]): DDL = new SequenceDDLBuilder(seq).buildDDL

  class QueryBuilder(ast: Node, linearizer: ValueLinearizer[_]) extends super.QueryBuilder(ast, linearizer) {

    override protected val mayLimit0 = false
    override protected val scalarFrom = Some("sysibm.sysdummy1")
    override protected val supportsTuples = false

    /*TODO
    override protected def expr(c: Node, rename: Boolean, topLevel: Boolean): Unit = {
      c match {
        /* Convert proper BOOLEANs which should be returned from a SELECT
         * statement into pseudo-boolean SMALLINT values 1 and 0 */
         case c: Column[_] if topLevel && !rename && b == selectSlot && c.typeMapper(profile) == profile.typeMapperDelegates.booleanTypeMapperDelegate =>
          b += "case when "
          innerExpr(c)
          b += " then 1 else 0 end"
        case _ => super.expr(c, rename, topLevel)
      }
    }
    */

    override protected def innerExpr(c: Node): Unit = c match {
      /* Create TRUE and FALSE values because Derby lacks boolean literals */
      case c @ ConstColumn(true) => b += "(1=1)"
      case c @ ConstColumn(false) => b += "(1=0)"

      /* Convert pseudo-booleans from tables and subqueries to real booleans */
      //TODO case n: NamedColumn[_] if n.typeMapper(profile) == profile.typeMapperDelegates.booleanTypeMapperDelegate =>
      b += "("; super.innerExpr(c); b += " != 0)"
      //case c @ SubqueryColumn(pos, sq, tm) if tm(profile) == profile.typeMapperDelegates.booleanTypeMapperDelegate =>
      //  b += "("; super.innerExpr(c); b += " != 0)"

      case EscFunction("ifnull", l, r) => r match {
        /* Derby does not support IFNULL so we use COALESCE instead,
         * and it requires NULLs to be casted to a suitable type */
        case c: Column[_] =>
          b += "coalesce(cast("
          expr(l)
          b += " as " += mapTypeName(c.typeMapper(driver)) += "),"
          expr(r); b += ")"
        case _ => throw new SLICKException("Cannot determine type of right-hand side for ifNull")
      }

      /*TODO case c @ BindColumn(v) if b == selectSlot =>
        /* The Derby embedded driver has a bug (DERBY-4671) which results in a
         * NullPointerException when using bind variables in a SELECT clause.
         * This should be fixed in Derby 10.6.1.1. The workaround is to add an
         * explicit type annotation (in the form of a CAST expression). */
        val tmd = c.typeMapper(profile)
        b += "cast("
        b +?= { (p, param) => tmd.setValue(v, p) }
        b += " as " += mapTypeName(tmd) += ")"
      */

      /* I guess NEXTVAL was too short */
      case Sequence.Nextval(seq) => b += "(next value for " += quoteIdentifier(seq.name) += ")"

      case Sequence.Currval(seq) => throw new SLICKException("Derby does not support CURRVAL")

      case EscFunction("database") => b += "''"

      case _ => super.innerExpr(c)
    }

    /*
    override protected def table(t: Node, name: String): Unit = t match {
      /* Derby requires columns of UNION parts to have the same names. If my
       * understanding of SQL:2008 is correct, this is a bug. This behavior
       * would be correct if the CORRESPONDING keyword was used for a UNION.
       * The workaround is to rename all parts with the same auto-generated
       * column names. */
      case Subquery(Union(all, sqs), rename) =>
        b += "("
        b.sep(sqs, (if(all) " UNION ALL " else " UNION "))(sq => subQueryBuilderFor(sq.asInstanceOf[Query[_,_]]).innerBuildSelect(b, rename))
        b += ") " += quoteIdentifier(name)
      case _ => super.table(t, name)
    }
    */
  }

  class DDLBuilder(table: AbstractBasicTable[_]) extends super.DDLBuilder(table) {
    override protected def createColumnDDLBuilder(c: RawNamedColumn) = new ColumnDDLBuilder(c)

    protected class ColumnDDLBuilder(column: RawNamedColumn) extends super.ColumnDDLBuilder(column) {
      override protected def appendOptions(sb: StringBuilder) {
        if(defaultLiteral ne null) sb append " DEFAULT " append defaultLiteral
        if(notNull) sb append " NOT NULL"
        if(primaryKey) sb append " PRIMARY KEY"
        if(autoIncrement) sb append " GENERATED BY DEFAULT AS IDENTITY"
      }
    }

    override protected def createIndex(idx: Index) = {
      if(idx.unique) {
        /* Create a UNIQUE CONSTRAINT (with an automatically generated backing
         * index) because Derby does not allow a FOREIGN KEY CONSTRAINT to
         * reference columns which have a UNIQUE INDEX but not a nominal UNIQUE
         * CONSTRAINT. */
        val sb = new StringBuilder append "ALTER TABLE " append quoteIdentifier(table.tableName) append " ADD "
        sb append "CONSTRAINT " append quoteIdentifier(idx.name) append " UNIQUE("
        addIndexColumnList(idx.on, sb, idx.table.tableName)
        sb append ")"
        sb.toString
      } else super.createIndex(idx)
    }
  }

  class SequenceDDLBuilder[T](seq: Sequence[T]) extends super.SequenceDDLBuilder(seq) {
    override def buildDDL: DDL = {
      import seq.integral._
      val increment = seq._increment.getOrElse(one)
      val desc = increment < zero
      val b = new StringBuilder append "CREATE SEQUENCE " append quoteIdentifier(seq.name)
      /* Set the START value explicitly because it defaults to the data type's
       * min/max value instead of the more conventional 1/-1. */
      b append " START WITH " append seq._start.getOrElse(if(desc) -1 else 1)
      seq._increment.foreach { b append " INCREMENT BY " append _ }
      seq._maxValue.foreach { b append " MAXVALUE " append _ }
      seq._minValue.foreach { b append " MINVALUE " append _ }
      /* Cycling is supported but does not conform to SQL:2008 semantics. Derby
       * cycles back to the START value instead of MINVALUE or MAXVALUE. No good
       * workaround available AFAICT. */
      if(seq._cycle) b append " CYCLE"
      new DDL {
        val createPhase1 = Iterable(b.toString)
        val createPhase2 = Nil
        val dropPhase1 = Nil
        val dropPhase2 = Iterable("DROP SEQUENCE " + quoteIdentifier(seq.name))
      }
    }
  }

  class TypeMapperDelegates extends super.TypeMapperDelegates {
    override val booleanTypeMapperDelegate = new BooleanTypeMapperDelegate
    override val byteTypeMapperDelegate = new ByteTypeMapperDelegate
    override val uuidTypeMapperDelegate = new UUIDTypeMapperDelegate {
      override def sqlType = java.sql.Types.BINARY
      override def sqlTypeName = "CHAR(16) FOR BIT DATA"
    }

    /* Derby does not have a proper BOOLEAN type. The suggested workaround is
     * SMALLINT with constants 1 and 0 for TRUE and FALSE. */
    class BooleanTypeMapperDelegate extends super.BooleanTypeMapperDelegate {
      override def sqlTypeName = "SMALLINT"
      override def valueToSQLLiteral(value: Boolean) = if(value) "1" else "0"
    }
    /* Derby does not have a TINYINT type, so we use SMALLINT instead. */
    class ByteTypeMapperDelegate extends super.ByteTypeMapperDelegate {
      override def sqlTypeName = "SMALLINT"
    }
  }
}

object DerbyDriver extends DerbyDriver
