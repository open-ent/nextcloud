const rights = {
    workflow: {
        access: 'fr.openent.nextcloud.controller.NextcloudController|view'
    },
    // Miroir des droits resource du module workspace (Behaviours.register('workspace', ...)) :
    // les documents nextcloud restent stockés via le workspace standard (mêmes actions
    // WorkspaceController), mais le calcul de myRights passe par CE service ('nextcloud') plutôt
    // que 'workspace' pour tout document dont application === 'nextcloud'. Sans cet objet
    // resource, myRights restait vide (aucune clé manager/comment/contrib/read jamais posée),
    // masquant Propriétés/Partager/Déplacer/Copier/Supprimer dans la barre d'actions native.
    resource: {
        comment: {
            right: 'org-entcore-workspace-controllers-WorkspaceController|commentDocument'
        },
        contrib: {
            right: "org-entcore-workspace-controllers-WorkspaceController|updateDocument"
        },
        read: {
            right: 'org-entcore-workspace-controllers-WorkspaceController|getDocument'
        },
        manager: {
            right: 'org-entcore-workspace-controllers-WorkspaceController|shareJson'
        }
    }
};
export default rights;