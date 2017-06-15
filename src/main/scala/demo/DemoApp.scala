package demo

import com.softwaremill.quicklens._
import demo.styles._
import org.scalajs.dom
import org.scalajs.dom.{Event, EventTarget, console}
import outwatch.Sink
import outwatch.dom.{Handlers, OutWatch, VNode}
import outwatch.extras._
import outwatch.styles.Styles
import rxscalajs.Observable
import rxscalajs.Observable.Creator
import rxscalajs.subscription.AnonymousSubscription

import scala.reflect.ClassTag
import scala.scalajs.js
import scala.scalajs.js.{Date, JSApp}
import scala.util.Random
import scalacss.DevDefaults._



object Logger extends Component with
                      LogAreaStyle {
  case class LogAction(action: String) extends Action

  case class State(
    log: Seq[String] = Seq("Log:")
  )

  private def now = (new Date).toLocaleString()

  val reducer: Reducer = {
    case (state, LogAction(line)) =>
      console.log(s"Log $line")
      modify(state)(_.log).using(_ :+ s"$now : $line")
  }

  override val effects: EffectsHandler = {
    case (_, TodoModule.AddTodo(s)) =>
      console.log("Add todo effect")
      Observable.empty
  }

  def apply(store: Store[State, Action], stl: Style = defaultStyle): VNode = {
    import outwatch.dom._

    textarea(stl.textfield, stl.material,
      child <-- store.map(_.log.mkString("\n"))
    )
  }
}

object TextField extends TextFieldStyle {

  def apply(actions: Sink[String], minLen : Int = 4, stl: Style = defaultStyle): VNode = {
    import outwatch.dom._

    val inputTodo = createStringHandler()

    val disabledValues = inputTodo
      .map(_.length < minLen)
      .startWith(true)

    val filterSinkDisabled = (act: Observable[String]) =>
      act.withLatestFrom(disabledValues)
        .filter(x => !x._2)
        .map(_._1)

    val filteredActions = actions.redirect(filterSinkDisabled)
    val inputTodoFiltered = inputTodo.redirect(filterSinkDisabled)

    val enterdown = keydown.filter(k => k.keyCode == 13)

    div(
      div(stl.textfield, stl.material,
        label(stl.textlabel, "Enter todo"),
        input(stl.textinput,
          inputString --> inputTodo,
          value <-- inputTodo,
          enterdown(inputTodo) --> filteredActions,
          enterdown("") --> inputTodoFiltered
        )
      ),
      button(stl.button, stl.material,
        click(inputTodo) --> filteredActions,
        click("") --> inputTodoFiltered,
        disabled <-- disabledValues,
        "Submit"
      )
    )
  }

}


object TodoModule extends Component with
                          TodoModuleStyle {

  import Logger.LogAction

  case class AddTodo(value: String) extends Action
  case class RemoveTodo(todo: Todo) extends Action

  case class Todo(id: Int, value: String)
  case class State(todos: Seq[Todo] = Seq.empty)

  private def newID = Random.nextInt

  val reducer: Reducer = {
    case (state, AddTodo(value)) =>
      modify(state)(_.todos).using(_ :+ Todo(newID, value))
    case (state, RemoveTodo(todo)) =>
      modify(state)(_.todos).using(_.filter(_.id != todo.id))
  }

  // simulate some async effects by logging actions with a delay
  override val effects: EffectsHandler = {
    case (state, AddTodo(s)) =>
      Observable.interval(2000).take(1)
        .mapTo(LogAction(s"Add ${if (state.todos.isEmpty) "first " else ""}action: $s"))
    case (_, RemoveTodo(todo)) =>
      Observable.interval(2000).take(1)
        .mapTo(LogAction(s"Remove action: ${todo.value}"))
  }


  private def todoItem(todo: Todo, actions: Sink[Action], stl: Style): VNode = {
    import outwatch.dom._
    li(
      span(todo.value),
      button(stl.button, stl.material, click(RemoveTodo(todo)) --> actions, "Delete")
    )
  }

  def apply(store: Store[State, Action], stl: Style = defaultStyle): VNode = {
    import outwatch.dom._

    val stringSink = store.redirect[String] { item => item.map(AddTodo) }

    val todoViews = store.map(_.todos.map(todoItem(_, store, stl)))

    div(
      TextField(stringSink),
      button(stl.button, stl.material,
        click(Router.LogPage(10)) --> store,
        "Log only"
      ),
      ul(children <-- todoViews)
    )
  }
}

object TodoComponent extends Component {
  import TodoModule.{AddTodo, RemoveTodo}

  case class State(
    lastAction: String = "None",
    todo: TodoModule.State = TodoModule.State(),
    log: Logger.State = Logger.State()
  )

  private val lastActionReducer: Reducer = {
    case (state, AddTodo(value)) => state.modify(_.lastAction).setTo(s"Add $value")
    case (state, RemoveTodo(todo)) => state.modify(_.lastAction).setTo(s"Remove ${todo.value}")
  }

  val reducer: Reducer = combineReducers(
    lastActionReducer,
    subReducer(TodoModule.reducer, modify(_)(_.todo)),
    subReducer(Logger.reducer, modify(_)(_.log))
  )

  override val effects: EffectsHandler = combineEffects(
    subEffectHandler(TodoModule.effects, _.todo),
    subEffectHandler(Logger.effects, _.log)
  )


  def apply(store: Store[State, Action]): VNode = {
    import outwatch.dom._

    table(
      tbody(
        tr(
          td("Last action: ", child <-- store.map(_.lastAction))
        ),
        tr(
          td(TodoModule(store.map(_.todo)))
        ),
        tr(
          td(Logger(store.map(_.log)))
        )
      )
    )
  }
}



object Router {

  private val actionSink = Handlers.createHandler[Action]()
  private var effectsSub : Option[AnonymousSubscription] = None

  private def createNode[State](
    initialState: => State,
    reducer: Component.ReducerFull[State],
    view: Store[State, Action] => VNode,
    effects: Effects.HandlerFull[State]
  ): VNode = {

    val effectsWithPageChange: Effects.HandlerFull[State] = (s,a) => effects(s,a) merge pageChange(s,a)
//
//    val initState = initialState
//    val source = actionSink
//      .scan(initState)(reducer)
//      .startWith(initState)
//      .publishReplay(1)
//      .refCount
//
//    effectsSub.foreach(_.unsubscribe())
//    effectsSub = Option(
//      actionSink <-- actionSink.withLatestFrom(source).flatMap{ case (a,s) => effectsWithPageChange(s,a)
//      }
//    )
//    view(Store(source, actionSink))


    val initStateAndEffects = (initialState, Observable.just[Action]())
    val source = actionSink
      .scan(initStateAndEffects) { case ((s, _), a) =>
        (reducer(s, a), effectsWithPageChange(s, a))
      }
      .startWith(initStateAndEffects)
      .publishReplay(1)
      .refCount

    effectsSub.foreach(_.unsubscribe())
    effectsSub = Option(actionSink <-- source.flatMap(_._2))
    view(Store(source.map(_._1), actionSink))
  }

  def createNode(component: Component)(
    initialState: component.State,
    creator: Store[component.State, Action] => VNode
  ): VNode = {
    createNode(initialState, component.reducerFull, creator, component.effectsFull)
  }
//
//  trait NodeCreator[C <: Component] {
//    val component: C
//    val initialState: component.State
//
//    def create(store: Store[component.State, Action]): VNode
//  }

  import outwatch.dom._

  trait Page extends Action
  case class TodoPage(num: Int) extends Page
  case class LogPage(last: Int) extends Page

  final case class Rule[Page, Target](
    parse: Path => Option[Page],
    path: Page => Option[Path],
    target: Page => Option[Target]
  )

  class RouterConfig[Page, Target](
    rules: Seq[Rule[Page, Target]],
    notFound: (Page, Target)
  ) {

    def parse(path: Path): Page = rules.find(_.parse(path).isDefined).flatMap(_.parse(path)).getOrElse(notFound._1)
    def path(page: Page): Path = rules.find(_.path(page).isDefined).flatMap(_.path(page)).getOrElse(path(notFound._1))
    def target(page: Page): Target = rules.find(_.target(page).isDefined).flatMap(_.target(page)).getOrElse(notFound._2)


  }

  object RouterConfig {

    case class RouterConfigBuilder[Page, Target](
      rules: Seq[Rule[Page, Target]]
    ) extends PathParser {

      implicit class route[P <: Page](rf: RouteFragment[P])(implicit ct: ClassTag[P]) {

        val route = rf.route

        def ~>(f: P => Target) : Rule[Page, Target] = Rule(
          route.parse,
          p => ct.unapply(p).map(route.pathFor),
          p => ct.unapply(p).map(f)
        )

        def ~>(f: => Target) : Rule[Page, Target] = Rule(
          route.parse,
          p => ct.unapply(p).map(route.pathFor),
          p => ct.unapply(p).map(p => f)
        )
      }


      def rules(r: Rule[Page, Target]*) = this.copy(rules = r)

      def notFound(t: (Page,Target)) = new RouterConfig[Page, Target](rules, t)
    }

    def apply[Page, Target] (builder: RouterConfigBuilder[Page, Target] => RouterConfig[Page, Target]) =
      builder(RouterConfigBuilder[Page, Target](Seq.empty))
  }


  val config = RouterConfig[Page, VNode] { cfg =>

    import cfg._

    cfg.rules(
      ("log" / int).xmap(LogPage.apply)(LogPage.unapply(_).get) ~> (p => createNode(Logger)(Logger.State(Seq(s"${p.last }")), Logger(_))),
      ("todo" / int).xmap(TodoPage.apply)(TodoPage.unapply(_).get) ~> createNode(TodoComponent)(TodoComponent.State(), TodoComponent(_))
    )
      .notFound(TodoPage(1) -> div("Not found"))
  }





  def pathToPage(path: Path): Page = {
    val rest = path.map { str =>
      val index = str.indexOf("#")
      str.substring(index+1)
    }
    config.parse(rest)
  }

  def pageToPath(page: Page): Path = {
    val path = config.path(page)

    val str = dom.document.location.href
    val index = str.indexOf("#")
    val prefix = str.substring(0, index + 1)

    val result = Path(prefix) + path
    console.log(""+result.value)
    result
  }

  def pageToNode(page: Page) : VNode = {
    config.target(page)
  }


  private def pageChange[S]: Effects.HandlerFull[S] = { (_, action) =>
    action match {
      case p: Page =>
        dom.window.history.pushState("", "", pageToPath(p).value)
        Observable.empty
      case _ =>
        Observable.empty
    }
  }

  private def eventListener(target: EventTarget, event: String): Observable[Event] =
    Observable.create { subscriber =>
      val eventHandler: js.Function1[Event, Unit] = (e: Event) => subscriber.next(e)
      target.addEventListener(event, eventHandler)
      val cancel: Creator = () => {
        target.removeEventListener(event, eventHandler)
        subscriber.complete()
      }
      cancel
    }


  val location = eventListener(dom.window, "popstate")
    .map(_ => Path(dom.document.location.href))
    .startWith(Path(dom.document.location.href))

  val pages = location.map(pathToPage) merge actionSink.collect { case e: Page => e }

  def apply(): VNode = {
    import outwatch.dom._
    div(child <-- pages.map(pageToNode))
  }

}




object DemoApp extends JSApp {

  def main(): Unit = {
    Styles.subscribe(_.addToDocument())
    OutWatch.render("#app", Router())
  }
}
