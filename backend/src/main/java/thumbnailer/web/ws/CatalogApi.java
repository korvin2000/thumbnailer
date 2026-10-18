package thumbnailer.web.ws;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumPatch;
import thumbnailer.album.AlbumQuery;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.EntityKind;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Containers;
import thumbnailer.source.Kind;

/**
 * Answers the catalog messages: entities, their cards and curation, bulk album edits, links and copies. A curation that
 * changes album text runs in two steps (catalog_plan.md §2.5): the catalog's write under its lock, then one ordinary
 * {@link AlbumService#patch} per album (stripe lock, then the catalog's read lock), never one inside the other.
 */
@Component
class CatalogApi {

    private static final Event OK = new Event.Ok(null);
    /** The last round of an entity delete removes the links left (one whose text no longer resolves to it cannot be rewritten). */
    private static final int DELETE_ROUNDS = 3;

    private final Catalog catalog;
    private final AlbumRepository repo;
    private final AlbumService albums;
    private final Containers containers;
    private final SettingsStore settings;

    CatalogApi(Catalog catalog, AlbumRepository repo, AlbumService albums, Containers containers, SettingsStore settings) {
        this.catalog = catalog;
        this.repo = repo;
        this.albums = albums;
        this.containers = containers;
        this.settings = settings;
    }

    Event handle(Request.CatalogRequest request) {
        return switch (request) {
            case Request.Entities(var kind, var text, var under, var unused, var sort, var offset, var limit) -> {
                boolean only = Boolean.TRUE.equals(unused);
                yield new Event.EntityList(catalog.entities(kind, text, under, only, sort == Request.Entities.Sort.COUNT, offset, limit),
                        catalog.count(kind, text, under, only));
            }
            case Request.GetEntity(var entityId) -> new Event.EntityCard(catalog.get(entityId));
            case Request.SaveEntity(var entity) -> new Event.EntityCard(catalog.save(entity));
            case Request.DeleteEntities(var ids) -> {
                var left = ids; // a save may name one again between its rewrite and its delete: that album is rewritten next round
                for (int round = 1; !left.isEmpty(); round++) {
                    for (var id : left)
                        if (catalog.entity(id).kind() != EntityKind.LIST) rewrite(catalog.albumIds(id), a -> catalog.linkPatch(a, id, false));
                    left = catalog.delete(left, round == DELETE_ROUNDS);
                }
                yield OK;
            }
            case Request.MergeEntities(var from, var into) -> new Event.EntityCard(catalog.get(catalog.merge(from, into).id()));
            case Request.ConfirmEntities(var ids) -> {
                catalog.confirm(ids);
                yield OK;
            }
            case Request.CreditAs(var model, var studio, var alias, var roles) -> {
                rewrite(catalog.creditAs(model, studio, alias, roles), a -> catalog.creditPatch(a, model, studio, alias));
                yield new Event.EntityCard(catalog.get(model));
            }
            case Request.BulkUpdate(var ids, var query, var patch) -> {
                if (patch == null) throw new IllegalArgumentException("patch is required");
                var selected = repo.select(ids, query);
                selected.forEach(a -> albums.patch(a.id(), patch));
                yield new Event.Ok(selected.size());
            }
            case Request.LinkAlbums(var ids, var query, var entityId) -> link(ids, query, entityId, true);
            case Request.UnlinkAlbums(var ids, var query, var entityId) -> link(ids, query, entityId, false);
            case Request.Copies(var albumId) -> new Event.CopyList(repo.byContentId(find(albumId).stamp()));
            case Request.CompareCopies(var a, var b) -> compare(find(a), find(b));
        };
    }

    /** Favorites lists are plain link rows; the text roles edit the albums' text, which the saves resolve. */
    private Event link(List<String> ids, AlbumQuery query, int entityId, boolean linked) {
        boolean list = catalog.entity(entityId).kind() == EntityKind.LIST;
        var selected = repo.select(ids, query);
        return new Event.Ok(list ? catalog.setList(selected.stream().map(a -> a.internal().seq()).toList(), entityId, linked)
                : rewrite(selected.stream().map(Album::id).toList(), a -> catalog.linkPatch(a, entityId, linked)));
    }

    /** One patch per album whose text the edit changes, computed under the album's lock from its text then; returns how many. */
    private int rewrite(List<String> ids, Function<Album, AlbumPatch> edit) {
        return (int) ids.stream().filter(id -> albums.patch(id, edit).isPresent()).count();
    }

    /**
     * Both albums' files as they are on disk (raw: ciphertext as is), counted as reads so an operation waits for them;
     * what deleting a directory album would remove beyond them (its sub-folders, a file the listing leaves out) differs too.
     */
    private Event compare(Album a, Album b) {
        var s = settings.get();
        Path pa = s.locate(a.path()), pb = s.locate(b.path());
        try (var ra = albums.read(a.id()); var rb = albums.read(b.id());
             var ca = containers.openRaw(a.kind(), pa); var cb = containers.openRaw(b.kind(), pb)) {
            var differing = new TreeSet<>(differing(ca, cb));
            if (a.kind() == Kind.DIRECTORY) differing.addAll(uncompared(pa, ca));
            if (b.kind() == Kind.DIRECTORY) differing.addAll(uncompared(pb, cb));
            return new Event.Comparison(differing.isEmpty(), List.copyOf(differing));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What a directory holds beside the files {@code c} lists (a delete takes the whole directory): sub-folders as {@code name/}. */
    private static List<String> uncompared(Path dir, AlbumContainer c) throws IOException {
        var listed = c.items().stream().map(AlbumContainer.Item::name).collect(Collectors.toSet());
        try (var children = Files.list(dir)) {
            return children.map(p -> p.getFileName() + (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ? "/" : ""))
                    .filter(n -> !listed.contains(n)).toList();
        }
    }

    /**
     * The entry names whose bytes differ: present in one only, of another size, or another SHA-256 of their streamed
     * bytes (one extraction pass per container, so a solid archive is read once). A name listed more than once in
     * either container always differs: extraction by name reads only one of its occurrences.
     */
    static List<String> differing(AlbumContainer a, AlbumContainer b) throws IOException {
        var duplicates = new HashSet<String>();
        var sizes = sizes(a, duplicates);
        var others = sizes(b, duplicates);
        var differing = new TreeSet<>(duplicates);
        var same = new ArrayList<String>();
        var names = new TreeSet<>(sizes.keySet());
        names.addAll(others.keySet());
        for (var name : names)
            (!duplicates.contains(name) && Objects.equals(sizes.get(name), others.get(name)) ? same : differing).add(name);
        var digests = digests(a, same);
        var otherDigests = digests(b, same);
        for (var name : same) if (!Arrays.equals(digests.get(name), otherDigests.get(name))) differing.add(name);
        return List.copyOf(differing);
    }

    /** Size by name; a name seen twice goes to {@code duplicates}. */
    private static Map<String, Long> sizes(AlbumContainer c, Set<String> duplicates) {
        var sizes = new HashMap<String, Long>();
        for (var i : c.items()) if (sizes.putIfAbsent(i.name(), i.bytes()) != null) duplicates.add(i.name());
        return sizes;
    }

    private static Map<String, byte[]> digests(AlbumContainer c, List<String> names) throws IOException {
        var digests = new ConcurrentHashMap<String, byte[]>(); // 7-Zip calls the sink from its own threads
        c.extract(names, (item, in) -> {
            var md = sha256();
            var buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) md.update(buffer, 0, n);
            digests.put(item.name(), md.digest());
        });
        return digests;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Album find(String albumId) {
        return repo.find(albumId).orElseThrow(() -> new NoSuchElementException("No album " + albumId));
    }
}
