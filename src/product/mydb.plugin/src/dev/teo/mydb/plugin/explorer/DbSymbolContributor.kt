package dev.teo.mydb.plugin.explorer

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.sql.psi.DbObjectElement

/**
 * Tables, views and columns of every introspected data source in Go to Symbol and Search Everywhere
 * choosing one opens its DDL like ⌘B.
 */
class DbSymbolContributor : ChooseByNameContributorEx, com.intellij.openapi.project.DumbAware {
    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        val project = scope.project ?: return
        for (o in objects(project)) if (!processor.process(o.name)) return
    }

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        for (o in objects(parameters.project)) if (o.name == name && !processor.process(o)) return
    }

    companion object {
        fun objects(project: Project): Sequence<DbObjectElement> = sequence {
            for (config in DataSourceStorage.getInstance(project).all()) {
                val model = ModelService.getInstance(project).model(config.id) ?: continue
                for (db in model.databases) for (s in db.schemas) for (t in s.tables) {
                    yield(DbObjectElement(project, config.id, db.name, s.name, t))
                    for (c in t.columns) yield(DbObjectElement(project, config.id, db.name, s.name, t, c))
                }
            }
        }
    }
}
