package jsscratchpad;
import static spark.Spark.get;
import static spark.Spark.put;
import static spark.Spark.delete;
import static spark.Spark.port;
import static spark.Spark.before;
import static spark.Spark.halt;
import static spark.Spark.post;
import static spark.Spark.staticFileLocation;
import static spark.Spark.stop;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import com.google.gson.Gson;

import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;
import spark.ModelAndView;
import spark.Session;
import spark.Spark;
import spark.template.freemarker.FreeMarkerEngine;

public class Main {

	public static void main(String[] args) {
		try {
	        Configuration cfg = new Configuration(Configuration.VERSION_2_3_23);
	        cfg.setClassForTemplateLoading(Main.class, "/public");
	        cfg.setDefaultEncoding("UTF-8");
	        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
	        cfg.setLogTemplateExceptions(false);
	        final FreeMarkerEngine dbEngine = new FreeMarkerEngine(cfg);
	        final Gson gson = new Gson();
			final DataSource dataSource = DatabaseUtil.getDatasource();
			SnippetUtil.createTable();
			UserSnippetUtil.createTable();
			UserUtil.createTable();
	        
			port(Integer.valueOf(System.getenv("PORT")));
			staticFileLocation("/public");
			
			final CanvasViewer viewer = new CanvasViewer();
			Spark.webSocket("/sketch/connect", viewer);

			get("/users", (request, response) -> {
				return UserUtil.getAllUsers();
			}, gson::toJson);
			
			post("/login", (req, res) -> {
				String username = req.queryParams("username");
				String password = req.queryParams("password");
				if(UserUtil.verify(username, password)) {
					Session s = req.session(true);
					s.maxInactiveInterval(60*30);
					s.attribute("username", username);
					res.cookie("username", username);
					res.status(200);
				} else {
					res.status(403);
				}
				return "";
			});
			
			post("/logout", (req, res) -> {
				Session s = req.session();
				if(s != null) {
					s.attribute("username", null);
					s.invalidate();
					res.status(200);
				} else {
					res.status(400);
				}
				return "";
			});

			before("/user/*", (request, response) -> {
			    if(request.session() == null || request.session().attribute("username") == null) {
				    halt(401, "Unauthorized, please sign in.");
			    }
			});
			
			get("/user/check", (request, response) -> {
			    return request.session().attribute("username");
			});

			get("/user/snippet/load/:id", (req, res) -> {
				String username = req.session().attribute("username");
				res.type("application/json");
				Integer id = Integer.parseInt(req.params(":id"));
				return UserSnippetUtil.getSnippetForUser(id, username);
			}, gson::toJson);

			delete("/user/snippet/delete/:id", (req, res) -> {
				String username = req.session().attribute("username");
				Integer id = Integer.parseInt(req.params(":id"));
				UserSnippetUtil.deleteSnippetForUser(id, username);
				res.status(200);
				return "";
			});
			
			get("/user/snippet/check/:title", (req, res) -> {
				String username = req.session().attribute("username");
				res.type("application/json");
				System.out.println(req.params(":title"));
				return UserSnippetUtil.snippetTitleExistsForUser(username, req.params(":title"));
			}, gson::toJson);

			get("/user/snippet/list", (req, res) -> {
				String username = req.session().attribute("username");
				res.type("application/json");
				return UserSnippetUtil.getAllSnippetInfoForUser(username);
			}, gson::toJson);
			
//			get("/user/snippet/all", (req, res) -> {
//				res.type("application/json");
//				return UserSnippetUtil.getAllUserSnippetsInfo();
//			}, gson::toJson);

			put("/user/snippet/update", (req, res) -> {
				String username = req.session().attribute("username");
				Integer id = Integer.parseInt(req.queryParams("id"));
				String snippet = req.queryParams("snippet");
				if(UserSnippetUtil.snippetIdExistsForUser(username, id)) {
					UserSnippetUtil.updateSnippetForUser(id, username, snippet);
					res.status(200);
					return id;
				} else {
					res.status(500);
					return "Snippet must exist first. Try Save As.";
				}
			});
			
			post("/user/snippet/save", (req, res) -> {
				String username = req.session().attribute("username");
				String title = req.queryParams("title");
				String snippet = req.queryParams("snippet");
				Integer id = UserSnippetUtil.saveSnippetAsForUser(username, title, snippet);
				res.status(200);
				return id;
			});
			
			post("/register", (req, res) -> {
				String username = req.queryParams("username");
				String password = req.queryParams("password");
				try {
					UserUtil.register(username, password);
					res.status(200);
					return "";
				} catch (RuntimeException e) {
					res.status(500);
					return e.getMessage();
				}
			});

			
			get("/", (request, response) -> {
				response.redirect("editor.html"); 
				return null;
			});
			
			get("/snippets/get", (req, res) -> {
				res.type("application/json");
				try (Connection connection = dataSource.getConnection();
					Statement stmt = connection.createStatement();
					ResultSet rs = stmt.executeQuery("SELECT * FROM snippet;");) {

					ArrayList<Snippet> output = new ArrayList<Snippet>();
					while (rs.next()) {
						Snippet l = new Snippet();
						l.setId(String.format("%03d", Integer.parseInt(rs.getString("id"))));
						l.setTitle(rs.getString("title"));
						l.setSnippet(rs.getString("snippet"));
						output.add(l);
					}
					return output;
				} catch (Exception e) {
					e.printStackTrace();
					return e.getMessage();
				}
			}, gson::toJson);
			
			get("/snippets/get/:snippetId", (req, res) -> {
				System.out.println(req.params(":snippetId"));
				res.type("application/json");
				try (Connection connection = dataSource.getConnection();
					PreparedStatement stmt = connection.prepareStatement("SELECT * FROM snippet WHERE id = ?;");) {
					
					stmt.setInt(1, Integer.parseInt(req.params(":snippetId")));
					
					try (ResultSet rs = stmt.executeQuery()) {
						if (rs.next()) {
							Snippet l = new Snippet();
							l.setId(rs.getString("id"));
							l.setTitle(rs.getString("title"));
							l.setSnippet(rs.getString("snippet"));
							return l;
						}
					}

					res.status(404);
					return null;
				} catch (Exception e) {
					return e.getMessage();
				}
			}, gson::toJson);
			
			
			post("/sketch/send", (req, res) -> {
				String id = req.queryParams("id");
				String code = req.queryParams("code");
				viewer.putCode(id, code);
				res.status(200);
				return "";
			});

			get("/sketch/get/:id", (req, res) -> {
				String id = req.params(":id");
				Map<String,String> test = new HashMap<String, String>();
				test.put("code",viewer.getCode(id));
				return new FreeMarkerEngine().render(new ModelAndView(test, "/canvasiframe.ftl"));
			});

			get("/sketch/deleteall", (req, res) -> {
				return "Deleted " + viewer.deleteAll();
			});
			

		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	public static void stopServer() {
		stop();
	}

	public static String renderSQL(Map<String, Object> model, String templatePath) {
		return new FreeMarkerEngine().render(new ModelAndView(model, templatePath));
	}
	
	public static String renderSQL(String templatePath) {
		return new FreeMarkerEngine().render(new ModelAndView(null, templatePath));
	}

	

}
