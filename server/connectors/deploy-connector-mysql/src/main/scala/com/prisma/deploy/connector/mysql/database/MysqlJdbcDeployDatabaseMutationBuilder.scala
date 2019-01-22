package com.prisma.deploy.connector.mysql.database

import com.prisma.connector.shared.jdbc.SlickDatabase
import com.prisma.deploy.connector.jdbc.database.{JdbcDeployDatabaseMutationBuilder, TypeMapper}
import com.prisma.shared.models.FieldBehaviour.IdBehaviour
import com.prisma.shared.models.Manifestations.RelationTable
import com.prisma.shared.models.{Model, Project, Relation, TypeIdentifier}
import com.prisma.shared.models.TypeIdentifier.ScalarTypeIdentifier
import com.prisma.utils.boolean.BooleanUtils
import org.jooq.impl.DSL
import slick.dbio.{DBIOAction => DatabaseAction}

import scala.concurrent.ExecutionContext

case class MySqlJdbcDeployDatabaseMutationBuilder(
    slickDatabase: SlickDatabase,
    typeMapper: TypeMapper
)(implicit val ec: ExecutionContext)
    extends JdbcDeployDatabaseMutationBuilder
    with BooleanUtils {

  import slickDatabase.profile.api._

  override def createSchema(projectId: String): DBIO[_] = {
    sqlu"CREATE SCHEMA #${qualify(projectId)}"
  }

  override def truncateProjectTables(project: Project): DBIO[_] = {
    val listTableNames: List[String] = project.models.flatMap { model =>
      model.fields.collect { case field if field.isScalar && field.isList => s"${model.dbName}_${field.dbName}" }
    }

    val tables = Vector("_RelayId") ++ project.models.map(_.dbName) ++ project.relations.map(_.relationTableName) ++ listTableNames
    val queries = tables.map(tableName => {
      changeDatabaseQueryToDBIO(sql.truncate(DSL.name(project.id, tableName)))()
    })

    DBIO.seq(sqlu"set foreign_key_checks=0" +: queries :+ sqlu"set foreign_key_checks=1": _*)
  }

  override def deleteProjectDatabase(projectId: String) = {
    sqlu"DROP DATABASE IF EXISTS #${qualify(projectId)}"
  }

  override def createModelTable(projectId: String, model: Model): DBIO[_] = {
    val idField              = model.idField_!
    val idFieldSQL           = typeMapper.rawSQLForField(idField)
    val initialSequenceValue = idField.behaviour.collect { case IdBehaviour(_, Some(seq)) => seq.initialValue }

    sqlu"""CREATE TABLE #${qualify(projectId, model.dbName)} (
           #$idFieldSQL,
           PRIMARY KEY (#${qualify(idField.dbName)}))
           #${if (idField.isAutoGeneratedByDb && initialSequenceValue.isDefined) s"auto_increment = ${initialSequenceValue.get}" else ""}
           DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"""
  }

  override def createScalarListTable(projectId: String, model: Model, fieldName: String, typeIdentifier: ScalarTypeIdentifier): DBIO[_] = {
    val indexSize = indexSizeForSQLType(typeMapper.rawSqlTypeForScalarTypeIdentifier(isList = false, typeIdentifier))
    val nodeIdSql = typeMapper.rawSQLFromParts("nodeId", isRequired = true, isList = false, TypeIdentifier.Cuid)
    val valueSql  = typeMapper.rawSQLFromParts("value", isRequired = true, isList = false, typeIdentifier)

    sqlu"""CREATE TABLE #${qualify(projectId, s"${model.dbName}_$fieldName")} (
           #$nodeIdSql,
           `position` INT(4) NOT NULL,
           #$valueSql,
           PRIMARY KEY (`nodeId`, `position`),
           INDEX `value` (`value`#$indexSize ASC),
           FOREIGN KEY (`nodeId`) REFERENCES #${qualify(projectId, model.dbName)} (#${qualify(model.idField_!.dbName)}) ON DELETE CASCADE)
           DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"""
  }

  override def createRelationTable(projectId: String, relation: Relation): DBIO[_] = {
    val relationTableName = relation.relationTableName
    val modelA            = relation.modelA
    val modelB            = relation.modelB
    val modelAColumn      = relation.modelAColumn
    val modelBColumn      = relation.modelBColumn
    val aColSql           = typeMapper.rawSQLFromParts(modelAColumn, isRequired = true, isList = false, modelA.idField_!.typeIdentifier)
    val bColSql           = typeMapper.rawSQLFromParts(modelBColumn, isRequired = true, isList = false, modelB.idField_!.typeIdentifier)

    // we do not create an index on A because queries for the A column can be satisfied with the combined index as well

    def legacyTableCreate(idColumn: String) = {
      val idSql = typeMapper.rawSQLFromParts(idColumn, isRequired = true, isList = false, TypeIdentifier.Cuid)
      sqlu"""
         CREATE TABLE #${qualify(projectId, relationTableName)} (
           #$idSql,
           PRIMARY KEY (`#$idColumn`),
           #$aColSql, 
           #$bColSql,
           INDEX `#$modelBColumn` (`#$modelBColumn` ASC),
           UNIQUE INDEX `#${relation.name}_AB_unique` (`#$modelAColumn` ASC, `#$modelBColumn` ASC),
           FOREIGN KEY (#$modelAColumn) REFERENCES #${qualify(projectId, modelA.dbName)}(#${qualify(modelA.dbNameOfIdField_!)}) ON DELETE CASCADE,
           FOREIGN KEY (#$modelBColumn) REFERENCES #${qualify(projectId, modelB.dbName)}(#${qualify(modelB.dbNameOfIdField_!)}) ON DELETE CASCADE)
           DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
      """
    }

    val modernTableCreate = sqlu"""
         CREATE TABLE #${qualify(projectId, relationTableName)} (
           #$aColSql,
           #$bColSql,
           INDEX `#$modelBColumn` (`#$modelBColumn` ASC),
           UNIQUE INDEX `#${relation.name}_AB_unique` (`#$modelAColumn` ASC, `#$modelBColumn` ASC),
           FOREIGN KEY (#$modelAColumn) REFERENCES #${qualify(projectId, modelA.dbName)}(#${qualify(modelA.dbNameOfIdField_!)}) ON DELETE CASCADE,
           FOREIGN KEY (#$modelBColumn) REFERENCES #${qualify(projectId, modelB.dbName)}(#${qualify(modelB.dbNameOfIdField_!)}) ON DELETE CASCADE)
           DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
      """

    relation.manifestation match {
      case None                                         => legacyTableCreate("id")
      case Some(RelationTable(_, _, _, Some(idColumn))) => legacyTableCreate(idColumn)
      case _                                            => modernTableCreate
    }
  }

  override def createRelationColumn(projectId: String, model: Model, references: Model, column: String): DBIO[_] = {
    val colSql = typeMapper.rawSQLFromParts(column, isRequired = false, isList = model.idField_!.isList, references.idField_!.typeIdentifier)
    sqlu"""ALTER TABLE #${qualify(projectId, model.dbName)}
          ADD COLUMN #$colSql,
          ADD FOREIGN KEY (#${qualify(column)}) REFERENCES #${qualify(projectId, references.dbName)}(#${qualify(references.idField_!.dbName)}) ON DELETE CASCADE;
        """
  }

  override def deleteRelationColumn(projectId: String, model: Model, references: Model, column: String): DBIO[_] = {
    for {
      namesOfForeignKey <- getNamesOfForeignKeyConstraints(projectId, model, column)
      _                 <- sqlu"""ALTER TABLE #${qualify(projectId, model.dbName)} DROP FOREIGN KEY `#${namesOfForeignKey.head}`;"""
      _                 <- sqlu"""ALTER TABLE #${qualify(projectId, model.dbName)} DROP COLUMN `#$column`;"""
    } yield ()
  }

  private def getNamesOfForeignKeyConstraints(projectId: String, model: Model, column: String): DatabaseAction[Vector[String], NoStream, Effect] = {
    for {
      result <- sql"""
            SELECT
              CONSTRAINT_NAME
            FROM
              INFORMATION_SCHEMA.KEY_COLUMN_USAGE
            WHERE
              REFERENCED_TABLE_SCHEMA = '#$projectId' AND
              TABLE_NAME = '#${model.dbName}' AND
              COLUMN_NAME = '#$column';
          """.as[String]
    } yield result
  }

  override def createColumn(projectId: String,
                            tableName: String,
                            columnName: String,
                            isRequired: Boolean,
                            isUnique: Boolean,
                            isList: Boolean,
                            typeIdentifier: ScalarTypeIdentifier): DBIO[_] = {
    val newColSql = typeMapper.rawSQLFromParts(columnName, isRequired = isRequired, isList = isList, typeIdentifier)
    val uniqueString =
      if (isUnique) {
        val indexSize = indexSizeForSQLType(typeMapper.rawSqlTypeForScalarTypeIdentifier(isList = isList, typeIdentifier))
        s", ADD UNIQUE INDEX ${qualify(s"${columnName}_UNIQUE")} (${qualify(columnName)}$indexSize ASC)"
      } else {
        ""
      }

    sqlu"""ALTER TABLE #${qualify(projectId, tableName)} ADD COLUMN #$newColSql #$uniqueString, ALGORITHM = INPLACE"""
  }

  override def deleteColumn(projectId: String, tableName: String, columnName: String, model: Option[Model]) = {
    sqlu"""ALTER TABLE #${qualify(projectId, tableName)} DROP COLUMN #${qualify(columnName)}"""
  }

  override def updateColumn(projectId: String,
                            model: Model,
                            oldColumnName: String,
                            newColumnName: String,
                            newIsRequired: Boolean,
                            newIsList: Boolean,
                            newTypeIdentifier: ScalarTypeIdentifier): DBIO[_] = {
    val newColSql = typeMapper.rawSQLFromParts(newColumnName, isRequired = newIsRequired, isList = newIsList, newTypeIdentifier)
    sqlu"ALTER TABLE #${qualify(projectId, model.dbName)} CHANGE COLUMN #${qualify(oldColumnName)} #$newColSql"
  }

  def indexSizeForSQLType(sql: String): String = sql match {
    case x if x.startsWith("text") | x.startsWith("mediumtext") => "(191)"
    case _                                                      => ""
  }

  override def addUniqueConstraint(projectId: String, tableName: String, columnName: String, typeIdentifier: ScalarTypeIdentifier): DBIO[_] = {
    val sqlType   = typeMapper.rawSqlTypeForScalarTypeIdentifier(isList = false, typeIdentifier)
    val indexSize = indexSizeForSQLType(sqlType)

    sqlu"ALTER TABLE #${qualify(projectId, tableName)} ADD UNIQUE INDEX #${qualify(s"${columnName}_UNIQUE")}(#${qualify(columnName)}#$indexSize ASC)"
  }

  override def removeIndex(projectId: String, tableName: String, indexName: String): DBIO[_] = {
    sqlu"ALTER TABLE #${qualify(projectId, tableName)} DROP INDEX #${qualify(indexName)}"
  }

  override def renameTable(projectId: String, oldTableName: String, newTableName: String): DBIO[_] = {
    if (oldTableName != newTableName) {
      sqlu"""ALTER TABLE #${qualify(projectId, oldTableName)} RENAME TO #${qualify(projectId, newTableName)}"""
    } else {
      DatabaseAction.successful(())
    }
  }

  override def renameColumn(projectId: String, tableName: String, oldColumnName: String, newColumnName: String): DBIO[_] = {
    if (oldColumnName != newColumnName) {
      sqlu"""ALTER TABLE #${qualify(projectId, tableName)} RENAME COLUMN #${qualify(oldColumnName)} TO #${qualify(newColumnName)}"""
    } else {
      DatabaseAction.successful(())
    }
  }
}
