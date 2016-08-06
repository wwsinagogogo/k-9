package com.fsck.k9.mailstore.migrations;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.support.annotation.VisibleForTesting;

import com.fsck.k9.controller.MessagingControllerCommands;
import com.fsck.k9.controller.MessagingControllerCommands.PendingCommand;
import com.fsck.k9.controller.PendingCommandSerializer;
import com.fsck.k9.helper.Utility;
import com.fsck.k9.mail.Flag;


public class MigrationTo56 {
    private static final String PENDING_COMMAND_MOVE_OR_COPY = "com.fsck.k9.MessagingController.moveOrCopy";
    private static final String PENDING_COMMAND_MOVE_OR_COPY_BULK = "com.fsck.k9.MessagingController.moveOrCopyBulk";
    private static final String PENDING_COMMAND_MOVE_OR_COPY_BULK_NEW = "com.fsck.k9.MessagingController.moveOrCopyBulkNew";
    private static final String PENDING_COMMAND_EMPTY_TRASH = "com.fsck.k9.MessagingController.emptyTrash";
    private static final String PENDING_COMMAND_SET_FLAG_BULK = "com.fsck.k9.MessagingController.setFlagBulk";
    private static final String PENDING_COMMAND_SET_FLAG = "com.fsck.k9.MessagingController.setFlag";
    private static final String PENDING_COMMAND_APPEND = "com.fsck.k9.MessagingController.append";
    private static final String PENDING_COMMAND_MARK_ALL_AS_READ = "com.fsck.k9.MessagingController.markAllAsRead";
    private static final String PENDING_COMMAND_EXPUNGE = "com.fsck.k9.MessagingController.expunge";


    public static void migratePendingCommands(SQLiteDatabase db) {
        List<PendingCommand> pendingCommmands = new ArrayList<>();
        for (OldPendingCommand oldPendingCommand : getPendingCommands(db)) {
            PendingCommand newPendingCommand = migratePendingCommand(oldPendingCommand);
            pendingCommmands.add(newPendingCommand);
        }

        db.execSQL("DROP TABLE IF EXISTS pending_commands");
        db.execSQL("CREATE TABLE pending_commands " +
                "(id INTEGER PRIMARY KEY, command TEXT, data TEXT)");

        PendingCommandSerializer pendingCommandSerializer = PendingCommandSerializer.getInstance();
        for (PendingCommand pendingCommand : pendingCommmands) {
            final ContentValues cv = new ContentValues();
            cv.put("command", pendingCommand.getCommandName());
            cv.put("data", pendingCommandSerializer.serialize(pendingCommand));
            db.insert("pending_commands", "command", cv);
        }
    }

    @VisibleForTesting
    static PendingCommand migratePendingCommand(OldPendingCommand oldPendingCommand) {
        switch (oldPendingCommand.command) {
            case PENDING_COMMAND_APPEND:
                return migrateCommandAppend(oldPendingCommand);
            case PENDING_COMMAND_SET_FLAG_BULK:
                return migrateCommandSetFlagBulk(oldPendingCommand);
            case PENDING_COMMAND_SET_FLAG:
                return migrateCommandSetFlag(oldPendingCommand);
            case PENDING_COMMAND_MARK_ALL_AS_READ:
                return migrateCommandMarkAllAsRead(oldPendingCommand);
            case PENDING_COMMAND_MOVE_OR_COPY_BULK:
                return migrateCommandMoveOrCopyBulk(oldPendingCommand);
            case PENDING_COMMAND_MOVE_OR_COPY_BULK_NEW:
                return migrateCommandMoveOrCopyBulkNew(oldPendingCommand);
            case PENDING_COMMAND_MOVE_OR_COPY:
                return migrateCommandMoveOrCopy(oldPendingCommand);
            case PENDING_COMMAND_EMPTY_TRASH:
                return migrateCommandEmptyTrash();
            case PENDING_COMMAND_EXPUNGE:
                return migrateCommandExpunge(oldPendingCommand);
            default:
                throw new IllegalArgumentException("Tried to migrate unknown pending command!");
        }
    }

    private static PendingCommand migrateCommandExpunge(OldPendingCommand command) {
        String folder = command.arguments[0];
        return MessagingControllerCommands.createExpunge(folder);
    }

    private static PendingCommand migrateCommandEmptyTrash() {
        return MessagingControllerCommands.createEmptyTrash();
    }

    private static PendingCommand migrateCommandMoveOrCopy(OldPendingCommand command) {
        String srcFolder = command.arguments[0];
        String uid = command.arguments[1];
        String destFolder = command.arguments[2];
        boolean isCopy = Boolean.parseBoolean(command.arguments[3]);

        return MessagingControllerCommands.createMoveOrCopyBulk(srcFolder, destFolder, isCopy,
                Collections.singletonList(uid));
    }

    private static PendingCommand migrateCommandMoveOrCopyBulkNew(OldPendingCommand command) {
        String srcFolder = command.arguments[0];
        String destFolder = command.arguments[1];
        boolean isCopy = Boolean.parseBoolean(command.arguments[2]);
        boolean hasNewUids = Boolean.parseBoolean(command.arguments[3]);

        if (hasNewUids) {
            Map<String, String> uidMap = new HashMap<>();
            int offset = (command.arguments.length - 4) / 2;
            for (int i = 4; i < 4 + offset; i++) {
                uidMap.put(command.arguments[i], command.arguments[i + offset]);
            }

            return MessagingControllerCommands.createMoveOrCopyBulk(srcFolder, destFolder, isCopy, uidMap);
        } else {
            List<String> uids = new ArrayList<>(command.arguments.length -4);
            uids.addAll(Arrays.asList(command.arguments).subList(4, command.arguments.length));

            return MessagingControllerCommands.createMoveOrCopyBulk(srcFolder, destFolder, isCopy, uids);
        }

    }

    private static PendingCommand migrateCommandMoveOrCopyBulk(OldPendingCommand command) {
        int len = command.arguments.length;

        OldPendingCommand newCommand = new OldPendingCommand();
        newCommand.command = PENDING_COMMAND_MOVE_OR_COPY_BULK_NEW;
        newCommand.arguments = new String[len + 1];
        newCommand.arguments[0] = command.arguments[0];
        newCommand.arguments[1] = command.arguments[1];
        newCommand.arguments[2] = command.arguments[2];
        newCommand.arguments[3] = Boolean.toString(false);
        System.arraycopy(command.arguments, 3, newCommand.arguments, 4, len - 3);

        return migratePendingCommand(newCommand);
    }

    private static PendingCommand migrateCommandMarkAllAsRead(OldPendingCommand command) {
        return MessagingControllerCommands.createMarkAllAsRead(command.arguments[0]);
    }

    private static PendingCommand migrateCommandSetFlag(OldPendingCommand command) {
        String folder = command.arguments[0];
        String uid = command.arguments[1];
        boolean newState = Boolean.parseBoolean(command.arguments[2]);
        Flag flag = Flag.valueOf(command.arguments[3]);

        return MessagingControllerCommands.createSetFlag(folder, newState, flag, Collections.singletonList(uid));
    }

    private static PendingCommand migrateCommandSetFlagBulk(OldPendingCommand command) {
        String folder = command.arguments[0];
        boolean newState = Boolean.parseBoolean(command.arguments[1]);
        Flag flag = Flag.valueOf(command.arguments[2]);

        List<String> uids = new ArrayList<>(command.arguments.length -3);
        uids.addAll(Arrays.asList(command.arguments).subList(3, command.arguments.length));

        return MessagingControllerCommands.createSetFlag(folder, newState, flag, uids);
    }

    private static PendingCommand migrateCommandAppend(OldPendingCommand command) {
        String folder = command.arguments[0];
        String uid = command.arguments[1];
        return MessagingControllerCommands.createAppend(folder, uid);
    }

    private static List<OldPendingCommand> getPendingCommands(SQLiteDatabase db) {
        Cursor cursor = null;
        try {
            cursor = db.query("pending_commands",
                    new String[] { "id", "command", "arguments" }, null, null, null, null, "id ASC");
            List<OldPendingCommand> commands = new ArrayList<>();
            while (cursor.moveToNext()) {
                OldPendingCommand command = new OldPendingCommand();
                command.command = cursor.getString(1);
                String arguments = cursor.getString(2);
                command.arguments = arguments.split(",");
                for (int i = 0; i < command.arguments.length; i++) {
                    command.arguments[i] = Utility.fastUrlDecode(command.arguments[i]);
                }
                commands.add(command);
            }
            return commands;
        } finally {
            Utility.closeQuietly(cursor);
        }
    }

    static class OldPendingCommand {
        public String command;
        public String[] arguments;
    }
}
