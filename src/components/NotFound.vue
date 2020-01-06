<template>
    <div>


    <h4>Страница не найдена (404 ошибка)</h4>

    <div class="alert alert-danger">
        Страница <b>{{ href }}</b> не существует
    </div>

    <p>
        Произошла ошибка при обработке вашего запроса.<br>
        Вероятно, подобной страницы не существовало, либо она была удалена с сервера.
    </p>

    </div>
</template>

<script>
export default {
    data: function() {
        return {
            href: window.location.href
        }
    }
}
</script>

<style scoped>

.alert {
    margin-top: 20px;
}

</style>